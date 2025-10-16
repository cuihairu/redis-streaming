package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.impl.RedisMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.impl.RedisMessageProducer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration example demonstrating the message queue functionality
 */
@Slf4j
@Tag("integration")
public class MessageQueueIntegrationExample {

    @Test
    public void testMessageQueueIntegration() throws Exception {
        // Create Redis configuration
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379");
        config.useSingleServer()
                .setAddress(redisUrl)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10);

        RedissonClient redissonClient = Redisson.create(config);

        try {
            runExample(redissonClient);
        } finally {
            redissonClient.shutdown();
        }
    }

    private static void runExample(RedissonClient redissonClient) throws Exception {
        String topic = "example-topic";
        MessageQueueFactory factory = new MessageQueueFactory(redissonClient);

        // Create producer and consumer
        MessageProducer producer = factory.createProducer();
        MessageConsumer consumer = factory.createConsumer("example-consumer");

        CountDownLatch messageProcessed = new CountDownLatch(3);

        // Set up message handler
        MessageHandler handler = message -> {
            log.info("Received message: {}", message.getPayload());
            messageProcessed.countDown();
            return MessageHandleResult.SUCCESS;
        };

        // Subscribe to topic
        consumer.subscribe(topic, "example-group", handler);
        consumer.start();

        // Send some messages
        for (int i = 0; i < 3; i++) {
            String payload = "Hello World " + i;
            CompletableFuture<String> future = producer.send(topic, payload);
            String messageId = future.get();
            log.info("Sent message '{}' with ID: {}", payload, messageId);
        }

        // Wait for messages to be processed
        boolean allProcessed = messageProcessed.await(10, TimeUnit.SECONDS);
        if (allProcessed) {
            log.info("All messages processed successfully!");
        } else {
            log.warn("Not all messages were processed within timeout");
        }

        // Demonstrate dead letter queue
        demonstrateDeadLetterQueue(redissonClient, factory, topic);

        // Clean up
        consumer.stop();
        consumer.close();
        producer.close();
    }

    @SuppressWarnings("deprecation")
    private static void demonstrateDeadLetterQueue(RedissonClient redissonClient,
                                                  MessageQueueFactory factory,
                                                  String topic) throws Exception {
        log.info("=== Dead Letter Queue Demo ===");

        MessageProducer producer = factory.createProducer();
        MessageConsumer consumer = factory.createConsumer("dlq-example-consumer");

        // Handler that always fails
        MessageHandler failingHandler = message -> {
            log.info("Processing message (will fail): {}", message.getPayload());
            return MessageHandleResult.RETRY; // Will eventually go to DLQ after max retries
        };

        consumer.subscribe(topic + "-fail", "dlq-group", failingHandler);
        consumer.start();

        // Send a message that will fail
        producer.send(topic + "-fail", "This message will fail").get();
        log.info("Sent message that will fail processing");

        // Wait for message to be processed and potentially sent to DLQ
        Thread.sleep(2000);

        // Check dead letter queue
        DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(redissonClient);
        long dlqSize = dlqManager.getDeadLetterQueueSize(topic + "-fail");
        log.info("Dead letter queue size: {}", dlqSize);

        // Clean up
        consumer.stop();
        consumer.close();
        producer.close();
    }
}
