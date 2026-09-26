package io.github.cuihairu.redis.streaming.mq.dlq;

import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Short-lived RedisDeadLetterConsumer.start() flows covering the background loop:
 * SUCCESS/RETRY/FAIL outcomes, handler exceptions, direct republish without a replay
 * handler, DlqConsumerAdapter mapping and the test-hold system property branch.
 */
@Tag("integration")
class DlqConsumerLoopIntegrationTest {

    private RedissonClient redis;
    private String uid;
    private String topic;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        redis = Redisson.create(cfg);
        uid = UUID.randomUUID().toString().substring(0, 8);
        topic = "it-mq-dlq-" + uid;
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("mq.dlq.test.holdBeforeHandleMs");
        System.clearProperty("mq.dlq.test.readAllIds");
        redis.getKeys().deleteByPattern("stream:topic:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:mq:*" + topic + "*");
        redis.shutdown();
    }

    private DeadLetterRecord record(String payload) {
        DeadLetterRecord r = new DeadLetterRecord();
        r.originalTopic = topic;
        r.payload = payload;
        r.retryCount = 1;
        r.maxRetries = 3;
        r.headers = new HashMap<>(Map.of("k", "v"));
        r.originalMessageId = "orig-" + payload;
        r.timestamp = Instant.now();
        return r;
    }

    private RStream<String, Object> dlqStream() {
        return redis.getStream(DlqKeys.dlq(topic));
    }

    private RStream<String, Object> partitionStream() {
        return redis.getStream(StreamKeys.partitionStream(topic, 0), org.redisson.client.codec.StringCodec.INSTANCE);
    }

    private boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dl) {
            if (Boolean.TRUE.equals(cond.call())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void loopHandlesSuccessRetryFailAndHandlerErrors() throws Exception {
        RedisDeadLetterService service = new RedisDeadLetterService(redis);
        service.send(record("ok-1"));
        service.send(record("retry-1"));
        service.send(record("fail-1"));
        service.send(record("boom-1"));

        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        RedisDeadLetterConsumer consumer =
                new RedisDeadLetterConsumer(redis, "dlqc-" + uid, "g-" + uid); // no replayHandler
        try {
            consumer.subscribe(topic, "g-" + uid, entry -> {
                String p = String.valueOf(entry.getPayload());
                handled.add(p);
                if (p.contains("boom")) {
                    throw new IllegalStateException("handler error");
                }
                if (p.contains("retry")) {
                    return DeadLetterConsumer.HandleResult.RETRY;
                }
                if (p.contains("fail")) {
                    return DeadLetterConsumer.HandleResult.FAIL;
                }
                return DeadLetterConsumer.HandleResult.SUCCESS;
            });
            consumer.start();

            assertTrue(waitUntil(() -> handled.size() >= 4, 20_000), "handled=" + handled);
            // RETRY without replay handler republishes to the original partition stream
            assertTrue(waitUntil(() -> partitionStream().size() > 0, 15_000),
                    "retry entry should be republished to partition stream");

            // success/retry/fail acked; the errored one remains pending
            assertTrue(waitUntil(() -> dlqStream().listPending(org.redisson.api.stream.StreamPendingRangeArgs
                    .groupName("g-" + uid).startId(StreamMessageId.MIN)
                    .endId(StreamMessageId.MAX).count(100)).size() <= 1, 15_000));
        } finally {
            consumer.close();
            assertTrue(consumer.isClosed());
        }
    }

    @Test
    void adapterMapsResultsAndReplaysThroughHandler() throws Exception {
        MqOptions options = MqOptions.builder().defaultDlqGroup("g-ad-" + uid).build();
        MessageQueueFactory mq = new MessageQueueFactory(redis, options);
        RedisDeadLetterService service = new RedisDeadLetterService(redis);
        service.send(record("ad-ok"));
        service.send(record("ad-retry"));
        service.send(record("ad-dead"));
        service.send(record("ad-fail"));

        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        AtomicInteger phase = new AtomicInteger();
        MessageConsumer consumer = mq.createDeadLetterConsumer("ad-c-" + uid);
        try {
            consumer.subscribe(topic, "g-ad-" + uid, m -> {
                handled.add(String.valueOf(m.getPayload()));
                String p = String.valueOf(m.getPayload());
                if (p.contains("retry")) {
                    return MessageHandleResult.RETRY;
                }
                if (p.contains("dead")) {
                    return MessageHandleResult.DEAD_LETTER;
                }
                if (p.contains("fail")) {
                    return MessageHandleResult.FAIL;
                }
                phase.incrementAndGet();
                return MessageHandleResult.SUCCESS;
            });
            consumer.start();

            // also exercise the 2-arg subscribe overload (its handler-adapting lambda)
            RedisDeadLetterService extra = new RedisDeadLetterService(redis);
            DeadLetterRecord er = record("ad-default-group");
            extra.send(er);
            consumer.subscribe(topic, m -> {
                handled.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.SUCCESS;
            });

            assertTrue(waitUntil(() -> handled.size() >= 5, 20_000), "handled=" + handled);
            // RETRY via adapter's replay lambda republished to the partition stream
            assertTrue(waitUntil(() -> partitionStream().size() > 0, 15_000),
                    "adapter RETRY should republish to partition stream");
        } finally {
            consumer.close();
        }
    }

    @Test
    void loopTestHoldPropertyBranchAndCreateForTopic() throws Exception {
        System.setProperty("mq.dlq.test.holdBeforeHandleMs", "30");
        System.setProperty("mq.dlq.test.readAllIds", "true");
        MqOptions options = MqOptions.builder().defaultDlqGroup("g-f2-" + uid).build();
        MessageQueueFactory mq = new MessageQueueFactory(redis, options);
        RedisDeadLetterService service = new RedisDeadLetterService(redis);
        service.send(record("f2-payload"));

        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        MessageConsumer consumer = mq.createDeadLetterConsumerForTopic(topic, null, "f2-c-" + uid,
                m -> {
                    handled.add(String.valueOf(m.getPayload()));
                    return MessageHandleResult.SUCCESS;
                });
        try {
            assertTrue(waitUntil(() -> !handled.isEmpty(), 20_000), "handled=" + handled);
        } finally {
            consumer.close();
        }
    }

    @Test
    void subscribeWithoutExplicitGroupUsesDefaultGroup() throws Exception {
        RedisDeadLetterService service = new RedisDeadLetterService(redis);
        service.send(record("grp-payload"));
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        RedisDeadLetterConsumer consumer =
                new RedisDeadLetterConsumer(redis, "dlqc2-" + uid, null);
        try {
            consumer.subscribe(topic, entry -> {
                handled.add(String.valueOf(entry.getPayload()));
                return DeadLetterConsumer.HandleResult.SUCCESS;
            });
            consumer.start();
            assertTrue(waitUntil(() -> !handled.isEmpty(), 20_000), "handled=" + handled);
        } finally {
            consumer.close();
        }
    }
}
