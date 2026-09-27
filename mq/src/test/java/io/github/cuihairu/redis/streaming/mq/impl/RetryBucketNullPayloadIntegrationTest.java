package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MQ-14: a null-payload message that goes through the scheduled-retry bucket must still
 * carry a NULL payload after the mover re-publishes it. The mover Lua used
 * {@code HGET ... or ''} and XADDed the payload field unconditionally, so the first retry
 * silently converted payload null -> "" and any handler branching on null changes behaviour
 * exactly once a retry happened.
 */
@Tag("integration")
class RetryBucketNullPayloadIntegrationTest {

    private RedissonClient redis;
    private String uid;
    private String topic;
    private RedisMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        redis = Redisson.create(cfg);
        uid = UUID.randomUUID().toString().substring(0, 8);
        topic = "it-mq-nullpl-" + uid;
    }

    @AfterEach
    void tearDown() {
        try {
            if (consumer != null) consumer.close();
        } catch (Exception ignore) {
        }
        redis.getKeys().deleteByPattern("stream:topic:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:mq:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:retry:*" + topic + "*");
        redis.shutdown();
    }

    private MqOptions bucketRetryOptions() {
        // backoff > 50ms forces the scheduled-retry BUCKET path (the <=50ms fast path
        // already preserves null via its removeIf(isNull)); the mover runs every second.
        return MqOptions.builder()
                .defaultPartitionCount(1)
                .workerThreads(1).schedulerThreads(2)
                .consumerPollTimeoutMs(150)
                .retryBaseBackoffMs(200).retryMaxBackoffMs(500)
                .leaseTtlSeconds(5).renewIntervalSec(1).rebalanceIntervalSec(1)
                .pendingScanIntervalSec(30).claimIdleMs(600_000)
                .retryMoverIntervalSec(1)
                .build();
    }

    private RedisMessageConsumer newConsumer(MqOptions options) {
        return new RedisMessageConsumer(redis, "npl-c-" + uid,
                new TopicPartitionRegistry(redis), options);
    }

    /** XADD an entry; when {@code withPayloadField} is false the payload field is absent entirely. */
    private void craftEntry(boolean withPayloadField, String payloadValue) {
        Map<String, Object> data = new HashMap<>();
        if (withPayloadField) {
            data.put("payload", payloadValue);
        }
        data.put("timestamp", Instant.now().toString());
        data.put("retryCount", "0");
        data.put("maxRetries", "5");
        data.put("topic", topic);
        data.put("partitionId", "0");
        stream(0).add(StreamAddArgs.entries(data));
    }

    private RStream<String, Object> stream(int pid) {
        return redis.getStream(StreamKeys.partitionStream(topic, pid), org.redisson.client.codec.StringCodec.INSTANCE);
    }

    private boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dl) {
            if (Boolean.TRUE.equals(cond.call())) return true;
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void nullPayloadSurvivesRetryBucketRoundTrip() throws Exception {
        consumer = newConsumer(bucketRetryOptions());
        List<Object> payloads = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        consumer.subscribe(topic, "g", m -> {
            payloads.add(m.getPayload());
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("force the bucket retry path");
            }
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();

        craftEntry(false, null); // entry WITHOUT a payload field == null payload

        assertTrue(waitUntil(() -> payloads.size() >= 2, 20_000),
                "mover must redeliver the entry, deliveries=" + payloads);
        assertNull(payloads.get(0), "first delivery carries a null payload");
        // MQ-14: after the bucket round trip the payload must STILL be null, not ""
        assertNull(payloads.get(1), "payload type must not change null -> \"\" across the retry bucket");
    }

    @Test
    void emptyStringPayloadStaysEmptyStringAcrossRetryBucket() throws Exception {
        // Control: the fix must not over-correct — an explicit "" payload is stored as ""
        // (HGET returns "", which is truthy in Lua) and must survive verbatim as "".
        consumer = newConsumer(bucketRetryOptions());
        List<Object> payloads = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        consumer.subscribe(topic, "g", m -> {
            payloads.add(m.getPayload());
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("force the bucket retry path");
            }
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();

        craftEntry(true, ""); // payload field present, empty string

        assertTrue(waitUntil(() -> payloads.size() >= 2, 20_000),
                "mover must redeliver the entry, deliveries=" + payloads);
        assertEquals("", payloads.get(0), "first delivery carries an empty-string payload");
        assertEquals("", payloads.get(1), "empty string must stay empty string (not become null)");
    }
}
