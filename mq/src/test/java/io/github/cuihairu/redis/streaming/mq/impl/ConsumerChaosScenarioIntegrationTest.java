package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DlqKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chaos scenario: continuous traffic with mixed outcomes (SUCCESS/RETRY/FAIL), flapping
 * subscriptions, pause/resume and a second consumer joining late. Exercises backpressure,
 * retry scheduling, dead-lettering, pending claim and rebalance interleavings in one pass.
 */
@Tag("integration")
class ConsumerChaosScenarioIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    /** Dump DLQ stream entries as {@code entryId{field=value,...}} lines, or null when absent/empty. */
    private static String dumpDlq(RedissonClient redis, String dlqKey) {
        try {
            Map<org.redisson.api.stream.StreamMessageId, Map<Object, Object>> entries =
                    redis.getStream(dlqKey)
                         .range(20, org.redisson.api.stream.StreamMessageId.MIN, org.redisson.api.stream.StreamMessageId.MAX);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            entries.forEach((id, fields) -> {
                sb.append(id).append('{');
                fields.forEach((k, v) -> sb.append(k).append('=').append(v).append(", "));
                sb.append("} ");
            });
            return sb.toString();
        } catch (Exception e) {
            return "read-failed: " + e;
        }
    }

    @Test
    void mixedOutcomesWithFlappingConsumers() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "chaos-" + uid;
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(2)
                .workerThreads(2).schedulerThreads(2)
                .consumerPollTimeoutMs(200)
                .maxInFlight(3)
                .retryMaxAttempts(2).retryBaseBackoffMs(100).retryMaxBackoffMs(300)
                .leaseTtlSeconds(3).renewIntervalSec(1).rebalanceIntervalSec(1)
                .pendingScanIntervalSec(1).claimIdleMs(600)
                .build();
        io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure(
                options.getKeyPrefix(), options.getStreamKeyPrefix());
        DlqKeys.configure(options.getStreamKeyPrefix());
        // Deliveries seen per payload. Keyed by payload, not Message.getId(): the
        // consumer-side id is the stream entry id of the current delivery and is
        // regenerated on every requeue, so it cannot identify a message across
        // redeliveries. The handler only returns RETRY on a payload's first delivery;
        // an unbounded retry roll would let a message burn through retryMaxAttempts
        // and dead-letter, i.e. the test would fail by construction (a dead-lettered
        // message never reaches "done") rather than by message loss.
        Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        CopyOnWriteArrayList<String> done = new CopyOnWriteArrayList<>();
        try {
            MessageQueueFactory mq = new MessageQueueFactory(redis, options);
            RedisMessageConsumer c1 = (RedisMessageConsumer) mq.createConsumer("chaos-c1-" + uid);
            c1.subscribe(topic, "g", m -> {
                boolean firstDelivery = deliveries
                        .computeIfAbsent(String.valueOf(m.getPayload()), k -> new AtomicInteger())
                        .incrementAndGet() == 1;
                if (firstDelivery && ThreadLocalRandom.current().nextInt(4) == 0) {
                    return MessageHandleResult.RETRY;
                }
                done.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.SUCCESS;
            }, null);
            c1.start();

            MessageProducer producer = mq.createProducer();
            for (int i = 0; i < 12; i++) {
                producer.send(topic, "k" + i, "msg-" + i).get(5, TimeUnit.SECONDS);
            }

            // second consumer joins mid-flight, triggering rebalance + claim
            RedisMessageConsumer c2 = (RedisMessageConsumer) mq.createConsumer("chaos-c2-" + uid);
            c2.subscribe(topic, "g", m -> {
                done.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.SUCCESS;
            }, null);
            c2.start();

            // pause/resume while backlog exists
            ((PausableMessageConsumer) c1).pause();
            Thread.sleep(400);
            ((PausableMessageConsumer) c1).resume();

            // Wait on the distinct-payload count: at-least-once delivery allows
            // duplicates (e.g. a lease-expiry claim of a slow in-flight message),
            // so the raw list size can legitimately exceed the number of messages.
            long deadline = System.currentTimeMillis() + 30_000;
            Set<String> processed = new HashSet<>(done);
            while (processed.size() < 12 && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
                processed = new HashSet<>(done);
            }
            // DLQ first: with retries bounded above nothing may dead-letter, and any
            // entry here would also explain a missing payload, so dump it for attribution.
            String dlqDump = dumpDlq(redis, DlqKeys.dlq(topic));
            assertNull(dlqDump,
                    "no message should end up in the DLQ under bounded retries; entries=" + dlqDump);
            assertEquals(12, processed.size(),
                    "all payloads should eventually be processed; got " + processed.size()
                    + " distinct of " + done.size() + " deliveries: " + processed
                    + "; deliveries-per-payload=" + deliveries);

            // unsubscribe mid-processing then stop/close both
            c1.unsubscribe(topic);
            c2.unsubscribe(topic);
            c1.stop();
            c2.stop();
            c1.close();
            c2.close();
            producer.close();
        } finally {
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.getKeys().deleteByPattern("streaming:mq:*" + topic + "*");
            redis.getKeys().deleteByPattern("streaming:retry:*" + topic + "*");
            redis.shutdown();
        }
    }
}
