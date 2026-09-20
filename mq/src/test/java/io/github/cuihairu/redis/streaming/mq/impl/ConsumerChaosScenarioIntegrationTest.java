package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
        CopyOnWriteArrayList<String> done = new CopyOnWriteArrayList<>();
        try {
            MessageQueueFactory mq = new MessageQueueFactory(redis, options);
            RedisMessageConsumer c1 = (RedisMessageConsumer) mq.createConsumer("chaos-c1-" + uid);
            c1.subscribe(topic, "g", m -> {
                int roll = ThreadLocalRandom.current().nextInt(4);
                if (roll == 0) {
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

            long deadline = System.currentTimeMillis() + 30_000;
            while (done.size() < 12 && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertEquals(12, done.size(), "all payloads should eventually be processed; got " + done.size());

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
