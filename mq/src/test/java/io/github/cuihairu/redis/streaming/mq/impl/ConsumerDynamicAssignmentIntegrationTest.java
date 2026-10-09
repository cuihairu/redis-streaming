package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
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
import org.redisson.config.Config;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P4 dynamic scaling, MQ layer: updatePartitionAssignment re-pins a live consumer to a
 * new modulo assignment — shrinking releases the out-of-assignment partitions promptly
 * (their workers stop and leases are released so another consumer of the group can take
 * over), growing picks up the newly eligible partitions on the next rebalance tick.
 */
@Tag("integration")
class ConsumerDynamicAssignmentIntegrationTest {

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
        topic = "it-mq-da-" + uid;
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

    private static MqOptions options() {
        return MqOptions.builder()
                .defaultPartitionCount(4)
                .workerThreads(4).schedulerThreads(2)
                .consumerPollTimeoutMs(150)
                .leaseTtlSeconds(30).renewIntervalSec(1).rebalanceIntervalSec(1)
                .pendingScanIntervalSec(30).claimIdleMs(600_000)
                .retryMoverIntervalSec(30)
                .build();
    }

    private void craftEntry(int pid, String payload) {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", payload);
        data.put("timestamp", Instant.now().toString());
        data.put("retryCount", "0");
        data.put("maxRetries", "3");
        data.put("topic", topic);
        data.put("partitionId", String.valueOf(pid));
        RStream<String, Object> s =
                redis.getStream(StreamKeys.partitionStream(topic, pid), org.redisson.client.codec.StringCodec.INSTANCE);
        s.add(org.redisson.api.stream.StreamAddArgs.entries(data));
    }

    private static boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
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
    void unknownTopicReturnsFalseAndSubscribedReturnsTrue() {
        consumer = new RedisMessageConsumer(redis, "da-q-" + uid, new TopicPartitionRegistry(redis), options());
        consumer.subscribe(topic, "g", m -> MessageHandleResult.SUCCESS);
        assertFalse(consumer.updatePartitionAssignment("no-such-topic", 2, 0));
        assertTrue(consumer.updatePartitionAssignment(topic, 2, 0));
    }

    @Test
    void assignmentUpdateReleasesShrunkPartitionsAndPicksUpGrownOnes() throws Exception {
        consumer = new RedisMessageConsumer(redis, "da-grow-" + uid, new TopicPartitionRegistry(redis), options());
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        consumer.subscribe(topic, "g", m -> {
            handled.add(String.valueOf(m.getPayload()));
            return MessageHandleResult.SUCCESS;
        }, SubscriptionOptions.builder().batchCount(1).pollTimeoutMs(100L)
                .partitionModulo(4).partitionRemainder(3).build());
        consumer.start();

        // only partition 3 is eligible: p0..p2 entries must stay untouched
        for (int pid = 0; pid < 4; pid++) {
            craftEntry(pid, "grow-" + pid);
        }
        assertTrue(waitUntil(() -> handled.stream().anyMatch(p -> p.contains("grow-3")), 15_000),
                "p3 handled, handled=" + handled);
        Thread.sleep(500);
        assertTrue(handled.stream().noneMatch(p -> p.contains("grow-0") || p.contains("grow-1") || p.contains("grow-2")),
                "pinned-away partitions untouched before reassignment, handled=" + handled);

        // grow the assignment to even partitions: p0/p2 become eligible, p1 stays pinned away,
        // p3 falls out and must stop being served
        assertTrue(consumer.updatePartitionAssignment(topic, 2, 0));

        assertTrue(waitUntil(() -> handled.stream().anyMatch(p -> p.contains("grow-0"))
                && handled.stream().anyMatch(p -> p.contains("grow-2")), 15_000),
                "newly eligible partitions picked up after reassignment, handled=" + handled);

        // let the p3 worker fully exit (poll timeout 150ms) before crafting the probe entry,
        // otherwise a still-draining worker could legitimately read it
        Thread.sleep(1_000);
        int before = handled.size();
        craftEntry(3, "after-shrink");
        Thread.sleep(2_500);
        assertEquals(before, handled.size(), "released partition must not be served after reassignment, handled=" + handled);
        assertTrue(handled.stream().noneMatch(p -> p.contains("after-shrink")), "handled=" + handled);
    }

    @Test
    void shrunkPartitionsAreHandedToTheNewOwner() throws Exception {
        MqOptions opts = options();
        CopyOnWriteArrayList<String> handledA = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<String> handledB = new CopyOnWriteArrayList<>();
        RedisMessageConsumer a = new RedisMessageConsumer(redis, "da-a-" + uid, new TopicPartitionRegistry(redis), opts);
        RedisMessageConsumer b = new RedisMessageConsumer(redis, "da-b-" + uid, new TopicPartitionRegistry(redis), opts);
        consumer = a; // for tearDown
        try {
            a.subscribe(topic, "g", m -> {
                handledA.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.SUCCESS;
            }, SubscriptionOptions.builder().batchCount(1).pollTimeoutMs(100L)
                    .partitionModulo(2).partitionRemainder(0).build());
            b.subscribe(topic, "g", m -> {
                handledB.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.SUCCESS;
            }, SubscriptionOptions.builder().batchCount(1).pollTimeoutMs(100L)
                    .partitionModulo(2).partitionRemainder(1).build());
            a.start();
            b.start();

            for (int pid = 0; pid < 4; pid++) {
                craftEntry(pid, "hand-" + pid);
            }
            assertTrue(waitUntil(() -> handledA.stream().anyMatch(p -> p.contains("hand-0"))
                    && handledB.stream().anyMatch(p -> p.contains("hand-1")), 15_000),
                    "both subtasks serve their half, a=" + handledA + " b=" + handledB);

            // scale in, mirroring what a runtime does: the removed subtask is stopped (its
            // workers release their leases on exit), the survivor is re-pinned to the full
            // partition set and picks the released partitions up on its next rebalance tick
            b.stop();
            assertTrue(a.updatePartitionAssignment(topic, 1, 0));
            // let B's workers fully exit before probing, so a draining worker cannot read it
            Thread.sleep(1_000);
            craftEntry(3, "handover-3");

            assertTrue(waitUntil(() -> handledA.stream().anyMatch(p -> p.contains("handover-3")), 15_000),
                    "released partition acquired by the re-pinned survivor, a=" + handledA);
            assertTrue(handledB.stream().noneMatch(p -> p.contains("handover-3")),
                    "stopped subtask must not serve the probe, b=" + handledB);
        } finally {
            try { b.close(); } catch (Exception ignore) { }
        }
    }
}
