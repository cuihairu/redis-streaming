package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamPendingRangeArgs;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQ-01 regression: the DLQ consumer read only {@code neverDelivered()} entries and the
 * failure paths (handler throws, RETRY replay fails) left ids in the consumer group PEL
 * forever — a stuck entry was never re-read and never acked. The pending sweep mirrors
 * the main consumer's convention: idle entries beyond the claim threshold are claimed
 * and run through the same handler disposition again.
 */
@Tag("integration")
class DlqPendingReclaimIntegrationTest {

    @BeforeAll
    static void fastSweep() {
        System.setProperty("mq.dlq.test.claimIdleMs", "500");
        System.setProperty("mq.dlq.test.pendingSweepMs", "300");
    }

    @AfterAll
    static void restoreSweep() {
        System.clearProperty("mq.dlq.test.claimIdleMs");
        System.clearProperty("mq.dlq.test.pendingSweepMs");
    }

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private static DeadLetterRecord record(String topic, String payload) {
        DeadLetterRecord r = new DeadLetterRecord();
        r.originalTopic = topic;
        r.payload = payload;
        r.retryCount = 1;
        r.maxRetries = 3;
        r.headers = new HashMap<>(java.util.Map.of("k", "v"));
        r.originalMessageId = "orig-" + payload;
        r.timestamp = Instant.now();
        return r;
    }

    private static long pendingCount(RedissonClient redis, String topic, String group) {
        RStream<String, Object> dlq = redis.getStream(DlqKeys.dlq(topic));
        return dlq.listPending(StreamPendingRangeArgs.groupName(group)
                .startId(StreamMessageId.MIN).endId(StreamMessageId.MAX).count(100)).size();
    }

    private static void awaitDeadline(String what, long timeoutMs, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    @Test
    void handlerExceptionIsRetriedFromPel() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "dlq-p1-" + uid;
        String group = "g-p1-" + uid;
        RedisDeadLetterService service = new RedisDeadLetterService(redis, (t, p, pl, h, mr) -> true);
        service.send(record(topic, "poison-handler"));
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(redis, "c-p1", group, (t, p, pl, h, mr) -> true);
        try {
            consumer.subscribe(topic, group, entry -> {
                handled.add(String.valueOf(entry.getPayload()));
                throw new IllegalStateException("handler keeps failing");
            });
            consumer.start();

            // old code: one delivery, then the id sits in the PEL forever (neverDelivered
            // only) — the sweep must re-claim it after the idle threshold
            awaitDeadline("handler retried from PEL", 20_000, () -> handled.size() >= 2);
            assertTrue(handled.size() >= 2,
                    "a persistently failing handler must be retried from the PEL, got " + handled.size());
            // the entry is never acked while failing, and stays in the stream itself
            assertTrue(pendingCount(redis, topic, group) >= 1, "failed entries stay pending, not dropped");
            assertTrue(redis.getStream(DlqKeys.dlq(topic)).size() >= 1, "the DLQ entry itself must remain");
        } finally {
            consumer.close();
            service.clear(topic);
            redis.getKeys().deleteByPattern(DlqKeys.dlq(topic));
            redis.shutdown();
        }
    }

    @Test
    void transientHandlerFailureEventuallyAcks() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "dlq-p2-" + uid;
        String group = "g-p2-" + uid;
        RedisDeadLetterService service = new RedisDeadLetterService(redis, (t, p, pl, h, mr) -> true);
        service.send(record(topic, "transient"));
        AtomicInteger calls = new AtomicInteger();
        RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(redis, "c-p2", group, (t, p, pl, h, mr) -> true);
        try {
            consumer.subscribe(topic, group, entry -> {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient failure");
                }
                return DeadLetterConsumer.HandleResult.SUCCESS;
            });
            consumer.start();

            awaitDeadline("first delivery", 20_000, () -> calls.get() >= 1);
            awaitDeadline("acked after transient failure", 20_000,
                    () -> pendingCount(redis, topic, group) == 0);
            assertEquals(0L, pendingCount(redis, topic, group),
                    "SUCCESS on the reclaimed delivery must ack the PEL entry");
            assertTrue(calls.get() >= 2, "the entry must have been redelivered, calls=" + calls.get());
        } finally {
            consumer.close();
            service.clear(topic);
            redis.getKeys().deleteByPattern(DlqKeys.dlq(topic));
            redis.shutdown();
        }
    }

    @Test
    void failedReplayIsRetriedUntilReplaySucceeds() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "dlq-p3-" + uid;
        String group = "g-p3-" + uid;
        RedisDeadLetterService service = new RedisDeadLetterService(redis, (t, p, pl, h, mr) -> true);
        service.send(record(topic, "replay-me"));
        AtomicInteger replays = new AtomicInteger();
        RedisDeadLetterConsumer consumer = new RedisDeadLetterConsumer(
                redis, "c-p3", group,
                (t, p, pl, h, mr) -> replays.incrementAndGet() > 1);   // first replay fails, next succeeds
        try {
            consumer.subscribe(topic, group, entry -> DeadLetterConsumer.HandleResult.RETRY);
            consumer.start();

            awaitDeadline("first delivery", 20_000, () -> replays.get() >= 1);
            awaitDeadline("acked after replay succeeds", 20_000,
                    () -> pendingCount(redis, topic, group) == 0);
            assertEquals(0L, pendingCount(redis, topic, group),
                    "a RETRY whose replay failed must stay pending and be reclaimed until replay succeeds");
            assertTrue(replays.get() >= 2, "the failed replay must have been retried, replays=" + replays.get());
        } finally {
            consumer.close();
            service.clear(topic);
            redis.getKeys().deleteByPattern(DlqKeys.dlq(topic));
            redis.shutdown();
        }
    }
}
