package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RetryMoverIntegrationTest {

    @Test
    void testRetryViaMoverThenDlq() throws Exception {
        String topic = "mover-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    .retryMaxAttempts(2)
                    .retryBaseBackoffMs(10) // use fast-path re-enqueue for stability
                    .retryMaxBackoffMs(20)
                    .retryMoverIntervalSec(1)
                    .consumerPollTimeoutMs(100)
                    .retryLockWaitMs(500)
                    .retryLockLeaseMs(1500)
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            MessageConsumer consumer = factory.createConsumer("c-mover");

            consumer.subscribe(topic, "g", m -> MessageHandleResult.RETRY);
            consumer.start();

            // Allow scheduler to start and initial rebalance/mover to schedule
            Thread.sleep(200);

            Message msg = new Message(topic, "k", "v");
            msg.setMaxRetries(1);
            producer.send(msg).get();

            DeadLetterQueueManager dlq = new DeadLetterQueueManager(client);

            boolean ok = false;
            for (int i = 0; i < 200; i++) { // up to ~20s for mover path
                long sz = dlq.getDeadLetterQueueSize(topic);
                if (sz > 0) { ok = true; break; }
                Thread.sleep(100);
            }
            assertTrue(ok, "expected message in DLQ after mover retry");

            consumer.stop();
            consumer.close();
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
