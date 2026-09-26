package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.DeadLetterQueueManager;
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
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MQ-06 regression: parse failures other than "payload missing" (e.g. malformed
 * timestamp, corrupt JSON) must be routed to DLQ and the original entry ACKed,
 * otherwise the pending scanner re-claims the same entry every cycle — infinite
 * loop + log spam.
 */
@Tag("integration")
class Mq06PoisonPendingIntegrationTest {

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
        topic = "it-mq06-" + uid;
    }

    @AfterEach
    void tearDown() {
        try {
            if (consumer != null) {
                consumer.close();
            }
        } catch (Exception ignore) {
        }
        redis.getKeys().deleteByPattern("stream:topic:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:mq:*" + topic + "*");
        redis.getKeys().deleteByPattern("streaming:retry:*" + topic + "*");
        redis.shutdown();
    }

    private RStream<String, Object> stream() {
        return redis.getStream(StreamKeys.partitionStream(topic, 0), org.redisson.client.codec.StringCodec.INSTANCE);
    }

    /**
     * Craft an entry with an invalid timestamp (not ISO-8601) that will make
     * {@link StreamEntryCodec#parsePartitionEntry} throw when {@link Instant#parse}
     * is invoked. The entry is then held as pending by a ghost consumer so our
     * consumer's pending scanner will try to claim it.
     */
    private StreamMessageId craftPoisonPending() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "poison-payload");
        data.put("timestamp", "not-a-valid-instant"); // will throw in Instant.parse
        data.put("retryCount", "0");
        data.put("maxRetries", "3");
        data.put("topic", topic);
        data.put("partitionId", "0");
        StreamMessageId id = stream().add(org.redisson.api.stream.StreamAddArgs.entries(data));
        try {
            stream().createGroup(org.redisson.api.stream.StreamCreateGroupArgs
                    .name("g").id(new StreamMessageId(0, 0)).makeStream());
        } catch (Exception ignore) {
        }
        // Read as ghost consumer to put it in PEL
        stream().readGroup("g", "ghost-" + uid,
                org.redisson.api.stream.StreamReadGroupArgs.neverDelivered().count(10));
        return id;
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
    void malformedTimestampPoisonEntryIsDlqdNotReclaimedInfinitely() throws Exception {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(1)
                .workerThreads(1).schedulerThreads(2)
                .consumerPollTimeoutMs(150)
                .retryBaseBackoffMs(0).retryMaxBackoffMs(0)
                .leaseTtlSeconds(5).renewIntervalSec(1).rebalanceIntervalSec(30)
                .pendingScanIntervalSec(1).claimIdleMs(300).claimBatchSize(10)
                .retryMoverIntervalSec(30)
                .build();

        craftPoisonPending();

        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        consumer = new RedisMessageConsumer(redis, "mq06-c-" + uid,
                new TopicPartitionRegistry(redis), options);
        consumer.subscribe(topic, "g", m -> {
            handled.add(String.valueOf(m.getPayload()));
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();

        // The poison entry should be claimed once, parsed (fails), sent to DLQ, and ACKed.
        // Handler must NOT be invoked (parse failed before handler).
        // Wait for DLQ to receive it.
        DeadLetterQueueManager dlq = new DeadLetterQueueManager(redis);
        assertTrue(waitUntil(() -> dlq.getDeadLetterQueueSize(topic) > 0, 20_000),
                "malformed timestamp poison entry must reach DLQ, handled=" + handled);

        // Handler must never have been called (parse exception happened before handler)
        assertTrue(handled.isEmpty(), "handler must not be invoked for parse-failure poison entry");

        // Give a few more scan cycles to ensure no re-claim loop (old code would keep re-claiming)
        Thread.sleep(5000);
        assertEquals(1, dlq.getDeadLetterQueueSize(topic),
                "DLQ size must remain 1 — no duplicate entries from re-claim loop");

        consumer.stop();
    }
}