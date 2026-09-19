package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lease/rebalance and pending-claim scenarios with two consumers on one partition group.
 */
@Tag("integration")
class ConsumerRebalanceClaimIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void firstConsumerClaimsPendingAfterPeerDies() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String topic = "rb-" + uid;
        MqOptions options = MqOptions.builder()
                .workerThreads(1).schedulerThreads(1)
                .consumerPollTimeoutMs(200)
                .leaseTtlSeconds(2).renewIntervalSec(1).rebalanceIntervalSec(1)
                .pendingScanIntervalSec(1).claimIdleMs(400).claimBatchSize(10)
                .build();
        try {
            MessageQueueFactory mq = new MessageQueueFactory(redis, options);
            MessageProducer producer = mq.createProducer();

            CopyOnWriteArrayList<String> a = new CopyOnWriteArrayList<>();
            RedisMessageConsumer dead = (RedisMessageConsumer) mq.createConsumer("c-dead-" + uid);
            dead.subscribe(topic, "g", m -> {
                a.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.RETRY; // stay pending so the peer can claim it
            }, null);
            dead.start();
            producer.send(topic, "k", "pending-1").get(5, java.util.concurrent.TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + 10_000;
            while (a.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(a.isEmpty(), "first consumer should have seen the message");

            // peer takes over after the dead consumer stops renewing
            CopyOnWriteArrayList<String> b = new CopyOnWriteArrayList<>();
            RedisMessageConsumer peer = (RedisMessageConsumer) mq.createConsumer("c-peer-" + uid);
            peer.subscribe(topic, "g", m -> {
                b.add(String.valueOf(m.getPayload()));
                return MessageHandleResult.SUCCESS;
            }, null);
            dead.stop();
            peer.start();
            deadline = System.currentTimeMillis() + 25_000;
            while (b.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
            assertFalse(b.isEmpty(), "peer should claim the pending message after lease expiry");
            peer.stop();
            peer.close();
            dead.close();
            producer.close();
        } finally {
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.getKeys().deleteByPattern("streaming:mq:lease:" + topic + "*");
            redis.shutdown();
        }
    }
}
