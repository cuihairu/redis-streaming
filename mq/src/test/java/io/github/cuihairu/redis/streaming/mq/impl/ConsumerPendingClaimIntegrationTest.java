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
 * Drives RedisMessageConsumer.processPendingMessages (lambda$processPendingMessages$3):
 * claim of ghost-consumer pending entries with SUCCESS results, handler failures and
 * missing payloads.
 */
@Tag("integration")
class ConsumerPendingClaimIntegrationTest {

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
        topic = "it-mq-pc-" + uid;
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

    private StreamMessageId craftAndHoldAsPending(String payload, String headersJson, String ghost) {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", payload);
        data.put("timestamp", Instant.now().toString());
        data.put("retryCount", "0");
        data.put("maxRetries", "3");
        data.put("topic", topic);
        data.put("partitionId", "0");
        if (headersJson != null) {
            data.put("headers", headersJson);
        }
        StreamMessageId id = stream().add(org.redisson.api.stream.StreamAddArgs.entries(data));
        try {
            stream().createGroup(org.redisson.api.stream.StreamCreateGroupArgs
                    .name("g").id(new StreamMessageId(0, 0)).makeStream());
        } catch (Exception ignore) {
        }
        stream().readGroup("g", ghost,
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
    void claimsGhostPendingEntriesWithSuccessFailureAndMissingPayload() throws Exception {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(1)
                .workerThreads(1).schedulerThreads(2)
                .consumerPollTimeoutMs(150)
                .retryBaseBackoffMs(0).retryMaxBackoffMs(0)
                .leaseTtlSeconds(5).renewIntervalSec(1).rebalanceIntervalSec(30)
                .pendingScanIntervalSec(1).claimIdleMs(300).claimBatchSize(10)
                .retryMoverIntervalSec(30)
                .build();
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();

        // hold three entries as pending of a ghost consumer before our consumer starts
        craftAndHoldAsPending("claimed-ok", null, "ghost-" + uid);
        craftAndHoldAsPending("claimed-boom", null, "ghost-" + uid);
        craftAndHoldAsPending("", "{\"" + PayloadHeaders.PAYLOAD_STORAGE_TYPE + "\":\"hash\",\""
                + PayloadHeaders.PAYLOAD_HASH_REF + "\":\"streaming:mq:payload:" + topic + ":p:0:nope\"}",
                "ghost-" + uid);

        consumer = new RedisMessageConsumer(redis, "pc-c-" + uid,
                new TopicPartitionRegistry(redis), options);
        consumer.subscribe(topic, "g", m -> {
            handled.add(String.valueOf(m.getPayload()));
            if (String.valueOf(m.getPayload()).contains("boom")) {
                throw new IllegalStateException("handler failed");
            }
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();

        assertTrue(waitUntil(() -> handled.stream().anyMatch(p -> p.contains("claimed-ok"))
                && handled.stream().anyMatch(p -> p.contains("claimed-boom")), 20_000),
                "claim path should handle pending entries, handled=" + handled);

        // claimed-ok acked by claim path; claimed-boom retried until exhausted then DLQ'd
        DeadLetterQueueManager dlq = new DeadLetterQueueManager(redis);
        assertTrue(waitUntil(() -> dlq.getDeadLetterQueueSize(topic) > 0, 20_000),
                "failing claimed entry should exhaust retries and reach DLQ");
        assertTrue(handled.stream().noneMatch(String::isEmpty),
                "missing-payload entry must never reach the handler, handled=" + handled);

        consumer.stop();
    }
}
