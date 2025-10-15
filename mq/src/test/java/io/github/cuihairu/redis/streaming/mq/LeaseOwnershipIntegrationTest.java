package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class LeaseOwnershipIntegrationTest {

    @Test
    void testSinglePartitionOwnedByOneConsumer() throws Exception {
        String topic = "lease-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder().defaultPartitionCount(1).build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();

            MessageConsumer c1 = factory.createConsumer("c1");
            MessageConsumer c2 = factory.createConsumer("c2");

            AtomicInteger h1 = new AtomicInteger();
            AtomicInteger h2 = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(10);

            MessageHandler hnd1 = m -> { h1.incrementAndGet(); done.countDown(); return MessageHandleResult.SUCCESS; };
            MessageHandler hnd2 = m -> { h2.incrementAndGet(); done.countDown(); return MessageHandleResult.SUCCESS; };

            c1.subscribe(topic, "g", hnd1);
            c2.subscribe(topic, "g", hnd2);
            c1.start();
            c2.start();

            for (int i = 0; i < 10; i++) producer.send(topic, "v" + i).get();

            boolean ok = done.await(5, TimeUnit.SECONDS);
            assertTrue(ok, "messages should be consumed");

            // Only one consumer should have processed (single partition, exclusive lease)
            assertTrue((h1.get() == 0 && h2.get() == 10) || (h2.get() == 0 && h1.get() == 10));

            c1.stop(); c2.stop();
            c1.close(); c2.close();
            producer.close();
        } finally {
            client.shutdown();
        }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

