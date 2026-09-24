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
 * Covers RedisMessageConsumer rebalance/lease paths: partition-modulo pinning, the
 * max-leased-partitions cap and lease-loss worker shutdown (renewLeases).
 */
@Tag("integration")
class ConsumerLeaseRebalanceIntegrationTest {

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
        topic = "it-mq-lr-" + uid;
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
    void partitionModuloPinsEvenPartitionsOnly() throws Exception {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(4)
                .workerThreads(4).schedulerThreads(2)
                .consumerPollTimeoutMs(150)
                .leaseTtlSeconds(5).renewIntervalSec(1).rebalanceIntervalSec(1)
                .pendingScanIntervalSec(30).claimIdleMs(600_000)
                .retryMoverIntervalSec(30)
                .build();
        consumer = new RedisMessageConsumer(redis, "lr-mod-" + uid,
                new TopicPartitionRegistry(redis), options);
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        consumer.subscribe(topic, "g", m -> {
            handled.add(String.valueOf(m.getPayload()));
            return MessageHandleResult.SUCCESS;
        }, SubscriptionOptions.builder().batchCount(1).pollTimeoutMs(100L)
                .partitionModulo(2).partitionRemainder(0).build());
        consumer.start();

        for (int pid = 0; pid < 4; pid++) {
            craftEntry(pid, "p" + pid);
        }

        assertTrue(waitUntil(() -> handled.stream().anyMatch(p -> p.contains("p0"))
                && handled.stream().anyMatch(p -> p.contains("p2")), 15_000),
                "even partitions must be handled, handled=" + handled);
        Thread.sleep(500);
        assertTrue(handled.stream().noneMatch(p -> p.contains("p1") || p.contains("p3")),
                "odd partitions are pinned away by modulo filter, handled=" + handled);
    }

    @Test
    void maxLeasedCapLimitsWorkersAndLeaseLossStopsWorker() throws Exception {
        MqOptions options = MqOptions.builder()
                .defaultPartitionCount(4)
                .workerThreads(4).schedulerThreads(2)
                .maxLeasedPartitionsPerConsumer(1)
                .consumerPollTimeoutMs(150)
                .retryBaseBackoffMs(0).retryMaxBackoffMs(0)
                .leaseTtlSeconds(30).renewIntervalSec(1).rebalanceIntervalSec(30)
                .pendingScanIntervalSec(30).claimIdleMs(600_000)
                .retryMoverIntervalSec(30)
                .build();
        consumer = new RedisMessageConsumer(redis, "lr-cap-" + uid,
                new TopicPartitionRegistry(redis), options);
        CopyOnWriteArrayList<String> handled = new CopyOnWriteArrayList<>();
        consumer.subscribe(topic, "g", m -> {
            handled.add(String.valueOf(m.getPayload()));
            return MessageHandleResult.SUCCESS;
        });
        consumer.start();

        for (int pid = 0; pid < 4; pid++) {
            craftEntry(pid, "cap-" + pid);
        }
        assertTrue(waitUntil(() -> handled.size() >= 1, 15_000), "handled=" + handled);
        Thread.sleep(700);
        assertTrue(handled.size() <= 1,
                "maxLeasedPartitionsPerConsumer=1 must cap active workers, handled=" + handled);

        // steal the lease: renewLeases must notice and stop the worker
        for (int pid = 0; pid < 4; pid++) {
            redis.getBucket(StreamKeys.lease(topic, "g", pid), org.redisson.client.codec.StringCodec.INSTANCE)
                    .set("intruder-" + uid, java.time.Duration.ofSeconds(60));
        }
        // allow at least one renew cycle (1s) to detect the loss and stop the worker
        Thread.sleep(2500);
        int handledBefore = handled.size();
        craftEntry(0, "after-steal");
        Thread.sleep(1500);
        assertEquals(handledBefore, handled.size(), "no processing after lease loss");

        consumer.stop();
    }
}
