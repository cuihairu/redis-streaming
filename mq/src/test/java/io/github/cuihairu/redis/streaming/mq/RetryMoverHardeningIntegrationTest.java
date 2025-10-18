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

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RetryMoverHardeningIntegrationTest {

    @Test
    void moverReenqueueAfterBackoff() throws Exception {
        String topic = "mover2-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .retryMaxAttempts(3)
                    .retryBaseBackoffMs(200)
                    .retryMaxBackoffMs(200)
                    .retryMoverIntervalSec(1)
                    .consumerPollTimeoutMs(100)
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            MessageConsumer consumer = factory.createConsumer("c-mover2");

            CountDownLatch attempts = new CountDownLatch(2);
            consumer.subscribe(topic, "g", m -> { attempts.countDown(); return MessageHandleResult.RETRY; });
            consumer.start();

            producer.send(topic, "k", "v").get(3, TimeUnit.SECONDS);

            // Expect at least two attempts: first delivery + one mover-based retry
            boolean ok = attempts.await(10, TimeUnit.SECONDS);
            assertTrue(ok, "expected at least two attempts with mover-based retry");

            consumer.stop(); consumer.close(); producer.close();
        } finally { client.shutdown(); }
    }

    private RedissonClient createClient() {
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer().setAddress(redisUrl).setConnectionMinimumIdleSize(1).setConnectionPoolSize(8);
        return Redisson.create(config);
    }
}

