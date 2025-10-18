package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class AckDeletePolicyIntegrationTest {

    @Test
    void immediateAckDeletesEntry() throws Exception {
        String topic = "ack-immediate-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).ackDeletePolicy("immediate").consumerPollTimeoutMs(100).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer p = factory.createProducer();
            MessageConsumer c = factory.createConsumer("c-imm");
            CountDownLatch handled = new CountDownLatch(1);
            c.subscribe(topic, "g", m -> { handled.countDown(); return MessageHandleResult.SUCCESS; });
            c.start();

            p.send(topic, "k", "v").get(3, TimeUnit.SECONDS);
            assertTrue(handled.await(3, TimeUnit.SECONDS));

            RStream<String,Object> s = client.getStream(StreamKeys.partitionStream(topic, 0), org.redisson.client.codec.StringCodec.INSTANCE);
            boolean ok = waitUntil(() -> s.size() == 0, 5000);
            assertTrue(ok, "stream should be empty after immediate ack delete");

            c.stop(); c.close(); p.close();
        } finally { client.shutdown(); }
    }

    @Test
    void allGroupsAckDeletesAfterBothAck() throws Exception {
        String topic = "ack-all-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).ackDeletePolicy("all-groups-ack").consumerPollTimeoutMs(100).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer p = factory.createProducer();
            MessageConsumer c1 = factory.createConsumer("c1");
            MessageConsumer c2 = factory.createConsumer("c2");
            CountDownLatch h1 = new CountDownLatch(1);
            CountDownLatch h2 = new CountDownLatch(1);
            c1.subscribe(topic, "g1", m -> { h1.countDown(); return MessageHandleResult.SUCCESS; });
            c2.subscribe(topic, "g2", m -> { h2.countDown(); return MessageHandleResult.SUCCESS; });
            c1.start(); c2.start();

            p.send(topic, "k", "v").get(3, TimeUnit.SECONDS);
            assertTrue(h1.await(3, TimeUnit.SECONDS));

            RStream<String,Object> s = client.getStream(StreamKeys.partitionStream(topic, 0), org.redisson.client.codec.StringCodec.INSTANCE);
            // After only g1 acked, entry may still exist
            assertTrue(s.isExists());

            assertTrue(h2.await(3, TimeUnit.SECONDS));

            boolean ok = waitUntil(() -> s.size() == 0, 5000);
            assertTrue(ok, "stream should be empty after both groups acked");

            c1.stop(); c1.close(); c2.stop(); c2.close(); p.close();
        } finally { client.shutdown(); }
    }

    private boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dl) {
            if (Boolean.TRUE.equals(cond.call())) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

