package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.DeadLetterQueueManager;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Short-lived subscribe()/start() flows driving RedisMessageConsumer.runPartitionWorker and
 * processIncomingRecord: SUCCESS/RETRY/DEAD_LETTER, handler exceptions with retry scheduling,
 * missing payloads (JSON-string headers) and lifecycle (unsubscribe/stop/close).
 */
@Tag("integration")
class ConsumerWorkerFlowIntegrationTest {

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
        topic = "it-mq-wf-" + uid;
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

    private RedisMessageConsumer newConsumer(MqOptions options) {
        return new RedisMessageConsumer(redis, "wf-c-" + uid,
                new TopicPartitionRegistry(redis), options);
    }

    private RStream<String, Object> stream(int pid) {
        return redis.getStream(StreamKeys.partitionStream(topic, pid), org.redisson.client.codec.StringCodec.INSTANCE);
    }

    private StreamMessageId craftEntry(int pid, String payload, int retryCount, int maxRetries, String headersJson) {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", payload);
        data.put("timestamp", Instant.now().toString());
        data.put("retryCount", String.valueOf(retryCount));
        data.put("maxRetries", String.valueOf(maxRetries));
        data.put("topic", topic);
        data.put("partitionId", String.valueOf(pid));
        if (headersJson != null) {
            data.put("headers", headersJson);
        }
        return stream(pid).add(org.redisson.api.stream.StreamAddArgs.entries(data));
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
    void successDeferAckRetryAndDeadLetterFlows() throws Exception {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(1)
                .workerThreads(1).schedulerThreads(2)
                .consumerPollTimeoutMs(150)
                .retryBaseBackoffMs(0).retryMaxBackoffMs(0)
                .leaseTtlSeconds(5).renewIntervalSec(1).rebalanceIntervalSec(1)
                .pendingScanIntervalSec(30).claimIdleMs(600_000)
                .retryMoverIntervalSec(30)
                .build();
        consumer = newConsumer(options);
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        consumer.subscribe(topic, "g", m -> {
            handled.add(String.valueOf(m.getPayload()));
            String p = String.valueOf(m.getPayload());
            if (p.startsWith("retry-once") && attempts.incrementAndGet() == 1) {
                return MessageHandleResult.RETRY;
            }
            if (p.startsWith("dlq-me")) {
                return MessageHandleResult.DEAD_LETTER;
            }
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();

        craftEntry(0, "plain", 0, 3, "{\"h\":\"v\"}");
        craftEntry(0, "deferred", 0, 3, "{\"" + MqHeaders.DEFER_ACK + "\":\"true\"}");
        craftEntry(0, "retry-once", 0, 3, null);
        craftEntry(0, "dlq-me", 0, 3, null);

        assertTrue(waitUntil(() -> handled.contains("plain") && handled.contains("deferred")
                && handled.contains("dlq-me") && handled.size() >= 4, 15_000), "handled=" + handled);
        assertTrue(waitUntil(() -> handled.stream().filter(x -> x.contains("retry-once")).count() >= 2, 15_000),
                "retry-once should be re-enqueued and redelivered, handled=" + handled);

        DeadLetterQueueManager dlq = new DeadLetterQueueManager(redis);
        assertTrue(waitUntil(() -> dlq.getDeadLetterQueueSize(topic) > 0, 10_000), "DEAD_LETTER should reach DLQ");

        // defer-ack entry must remain pending (not acked)
        assertEquals(1, stream(0).listPending(org.redisson.api.stream.StreamPendingRangeArgs
                .groupName("g").startId(StreamMessageId.MIN)
                .endId(StreamMessageId.MAX).count(100)).size());

        consumer.unsubscribe(topic);
        consumer.stop();
        consumer.stop(); // idempotent
        consumer.close();
        consumer.close(); // idempotent
    }

    @Test
    void handlerExceptionSchedulesRetryBucketAndMoverRedelivers() throws Exception {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(1)
                .workerThreads(1).schedulerThreads(2)
                .consumerPollTimeoutMs(150)
                .retryBaseBackoffMs(150).retryMaxBackoffMs(300)
                .leaseTtlSeconds(5).renewIntervalSec(1).rebalanceIntervalSec(1)
                .pendingScanIntervalSec(30).claimIdleMs(600_000)
                .retryMoverIntervalSec(1)
                .build();
        consumer = newConsumer(options);
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        consumer.subscribe(topic, "g", m -> {
            handled.add(String.valueOf(m.getPayload()));
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();

        craftEntry(0, "flaky", 0, 5, null);

        assertTrue(waitUntil(() -> handled.stream().filter(x -> x.contains("flaky")).count() >= 2, 20_000),
                "retry bucket + mover should redeliver, handled=" + handled);
    }

    @Test
    void exhaustedRetriesAndMissingPayloadsGoToDlq() throws Exception {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(1)
                .workerThreads(1).schedulerThreads(2)
                .consumerPollTimeoutMs(150)
                .retryBaseBackoffMs(0).retryMaxBackoffMs(0)
                .leaseTtlSeconds(5).renewIntervalSec(1).rebalanceIntervalSec(1)
                .pendingScanIntervalSec(30).claimIdleMs(600_000)
                .retryMoverIntervalSec(30)
                .build();
        consumer = newConsumer(options);
        AtomicInteger handled = new AtomicInteger();
        consumer.subscribe(topic, "g", m -> {
            handled.incrementAndGet();
            if (String.valueOf(m.getPayload()).contains("give-up")) {
                throw new IllegalStateException("always fails");
            }
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();

        // retryCount already at maxRetries -> immediate DLQ on first failure
        craftEntry(0, "give-up", 3, 3, null);
        // missing payload with JSON-string headers (broker-style entry)
        String missingRef = StreamKeys.controlPrefix() + ":payload:" + topic + ":p:0:missing-" + uid;
        craftEntry(0, "", 0, 3,
                "{\"" + PayloadHeaders.PAYLOAD_STORAGE_TYPE + "\":\"hash\",\""
                        + PayloadHeaders.PAYLOAD_HASH_REF + "\":\"" + missingRef + "\"}");

        DeadLetterQueueManager dlq = new DeadLetterQueueManager(redis);
        assertTrue(waitUntil(() -> dlq.getDeadLetterQueueSize(topic) >= 1, 15_000),
                "exhausted retries should reach DLQ, size=" + dlq.getDeadLetterQueueSize(topic));
        Map<StreamMessageId, Map<String, Object>> msgs = dlq.getDeadLetterMessages(topic, 10);
        assertTrue(msgs.values().stream().anyMatch(d ->
                String.valueOf(d.get("payload")).contains("give-up")), "give-up should be in DLQ: " + msgs);

        // missing-payload entry is acked (not left as poison pending) and never handled
        assertTrue(waitUntil(() -> stream(0).listPending(org.redisson.api.stream.StreamPendingRangeArgs
                .groupName("g").startId(StreamMessageId.MIN)
                .endId(StreamMessageId.MAX).count(100)).isEmpty(), 15_000),
                "missing payload entry must be acked out of the PEL");
        assertEquals(1, handled.get(), "only give-up should reach the handler");
    }

    @Test
    void closeBeforeStartIsSafeAndSubscribeAfterCloseThrows() throws Exception {
        consumer = newConsumer(MqOptions.builder().defaultPartitionCount(1).build());
        consumer.stop(); // never started
        assertThrows(IllegalStateException.class, () -> {
            consumer.close();
            consumer.subscribe(topic, "g", m -> MessageHandleResult.SUCCESS);
        });
    }
}
