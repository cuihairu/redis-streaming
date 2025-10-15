package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class RetryAndDlqIntegrationTest {

    @Test
    void testRetryThenDlqAndReplay() throws Exception {
        String topic = "retry-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient client = createClient();
        try {
            MqOptions opts = MqOptions.builder()
                    .defaultPartitionCount(1)
                    // Speed up consumer loop to reduce timing variance in CI
                    .consumerPollTimeoutMs(100)
                    .retryMaxAttempts(2)
                    .retryBaseBackoffMs(10)
                    .retryMaxBackoffMs(20)
                    .retryMoverIntervalSec(1)
                    .build();
            MessageQueueFactory factory = new MessageQueueFactory(client, opts);
            MessageProducer producer = factory.createProducer();
            MessageConsumer consumer = factory.createConsumer("c-retry");

            CountDownLatch handled = new CountDownLatch(3); // receive at least a few attempts
            consumer.subscribe(topic, "g", m -> {
                handled.countDown();
                return MessageHandleResult.RETRY; // always retry
            });
            consumer.start();

            Message msg = new Message(topic, "k", "hello");
            msg.setMaxRetries(1); // 1 attempt then DLQ
            producer.send(msg).get();

            DeadLetterQueueManager dlq = new DeadLetterQueueManager(client);

            // Also attach a lightweight DLQ consumer to robustly detect the DLQ entry arrival
            MessageConsumer dlqConsumer = factory.createDeadLetterConsumer(topic, "c-retry");
            CountDownLatch dlqSeen = new CountDownLatch(1);
            dlqConsumer.subscribe(topic, "g-dlq", m -> {
                dlqSeen.countDown();
                return MessageHandleResult.SUCCESS;
            });
            dlqConsumer.start();

            // Wait until DLQ receives the message (either by size() or by dlq consumer seeing it)
            boolean ok = false;
            for (int i = 0; i < 120; i++) { // up to ~12s
                long sz = dlq.getDeadLetterQueueSize(topic);
                if (sz > 0) { ok = true; break; }
                if (dlqSeen.getCount() == 0) { ok = true; break; }
                if (handled.getCount() <= 1) {
                    Thread.sleep(150);
                }
                Thread.sleep(100);
            }
            assertTrue(ok, "expected message in DLQ");

            // replay back to original topic
            var messages = dlq.getDeadLetterMessages(topic, 10);
            assertFalse(messages.isEmpty());
            var firstId = messages.keySet().iterator().next();
            assertTrue(dlq.replayMessage(topic, firstId));

            // ensure consumer processes replayed message
            boolean handledOk = handled.await(5, TimeUnit.SECONDS);
            assertTrue(handledOk);

            dlqConsumer.stop();
            dlqConsumer.close();
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
