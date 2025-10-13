package io.github.cuihairu.redis.streaming.examples.mq;

import io.github.cuihairu.redis.streaming.mq.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Message Queue comprehensive example
 * Demonstrates producer-consumer patterns, dead letter queues, and consumer groups
 */
@Slf4j
public class MessageQueueExample {

    private final ObjectMapper objectMapper;
    private RedissonClient redissonClient;
    private MessageQueueFactory factory;
    private final List<MessageConsumer> activeConsumers = new ArrayList<>();
    private final List<MessageProducer> activeProducers = new ArrayList<>();

    public MessageQueueExample() {
        // Configure ObjectMapper to handle Java 8 date/time types
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public static void main(String[] args) throws Exception {
        MessageQueueExample example = new MessageQueueExample();
        example.runExample();
    }

    public void runExample() throws Exception {
        log.info("Starting Message Queue Example");

        try {
            // Setup
            setupMessageQueue();

            // 1. Demonstrate basic producer-consumer
            demonstrateBasicProducerConsumer();

            // 2. Demonstrate consumer groups
            demonstrateConsumerGroups();

            // 3. Demonstrate dead letter queue
            demonstrateDeadLetterQueue();

            // 4. Demonstrate batch processing
            demonstrateBatchProcessing();

            // 5. Performance test
            performanceTest();

            log.info("Message Queue Example completed");

        } finally {
            cleanup();
        }
    }

    private void setupMessageQueue() {
        log.info("Setting up Redis Stream-based message queue");

        // Create Redis configuration
        Config config = new Config();
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        config.useSingleServer()
                .setAddress(redisUrl)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10);

        redissonClient = Redisson.create(config);
        factory = new MessageQueueFactory(redissonClient);
    }

    private void demonstrateBasicProducerConsumer() throws Exception {
        log.info("=== Demonstrating Basic Producer-Consumer ===");

        String topic = "orders";
        MessageProducer producer = factory.createProducer();
        MessageConsumer consumer = factory.createConsumer("basic-consumer");

        activeProducers.add(producer);
        activeConsumers.add(consumer);

        CountDownLatch consumeLatch = new CountDownLatch(5);

        // Set up message handler
        MessageHandler handler = message -> {
            try {
                OrderEvent event = objectMapper.readValue((String) message.getPayload(), OrderEvent.class);
                log.info("Consumed order event: {} for order: {} (amount: ${})",
                    event.getEventType(), event.getOrderId(), event.getAmount());
                consumeLatch.countDown();
                return MessageHandleResult.SUCCESS;
            } catch (Exception e) {
                log.error("Failed to process message", e);
                return MessageHandleResult.FAIL;
            }
        };

        // Subscribe and start consumer
        consumer.subscribe(topic, "basic-group", handler);
        consumer.start();

        // Produce some messages
        for (int i = 1; i <= 5; i++) {
            OrderEvent event = new OrderEvent(
                "order-" + i,
                i % 2 == 0 ? "ORDER_CREATED" : "ORDER_COMPLETED",
                50.0 + (i * 10),
                "user-" + i,
                Instant.now()
            );

            String payload = objectMapper.writeValueAsString(event);
            CompletableFuture<String> future = producer.send(topic, payload);
            String messageId = future.get();

            log.info("Produced order event: {} for order: {} (ID: {})",
                event.getEventType(), event.getOrderId(), messageId);

            Thread.sleep(200);
        }

        // Wait for consumption to complete
        boolean consumed = consumeLatch.await(10, TimeUnit.SECONDS);
        log.info("Basic producer-consumer test completed. All consumed: {}", consumed);

        // Note: Resources will be cleaned up in cleanup() method
    }

    private void demonstrateConsumerGroups() throws Exception {
        log.info("=== Demonstrating Consumer Groups ===");

        String topic = "notifications";
        MessageProducer producer = factory.createProducer();
        activeProducers.add(producer);

        // Create multiple consumers in different groups
        MessageConsumer emailConsumer = factory.createConsumer("email-processor");
        MessageConsumer smsConsumer = factory.createConsumer("sms-processor");
        MessageConsumer auditConsumer = factory.createConsumer("audit-processor");

        activeConsumers.add(emailConsumer);
        activeConsumers.add(smsConsumer);
        activeConsumers.add(auditConsumer);

        CountDownLatch emailLatch = new CountDownLatch(3);
        CountDownLatch smsLatch = new CountDownLatch(3);
        CountDownLatch auditLatch = new CountDownLatch(3);

        // Set up handlers for each consumer
        emailConsumer.subscribe(topic, "email-group", createNotificationHandler("EMAIL", emailLatch));
        smsConsumer.subscribe(topic, "sms-group", createNotificationHandler("SMS", smsLatch));
        auditConsumer.subscribe(topic, "audit-group", createNotificationHandler("AUDIT", auditLatch));

        // Start consumers
        emailConsumer.start();
        smsConsumer.start();
        auditConsumer.start();

        // Produce notification events
        for (int i = 1; i <= 3; i++) {
            NotificationEvent event = new NotificationEvent(
                "notification-" + i,
                "Order #" + i + " has been processed",
                "user-" + i,
                List.of("email", "sms"),
                Instant.now()
            );

            String payload = objectMapper.writeValueAsString(event);
            producer.send(topic, payload).get();

            log.info("Produced notification: {} for user: {}", event.getNotificationId(), event.getUserId());
            Thread.sleep(200);
        }

        // Wait for all groups to consume
        boolean emailComplete = emailLatch.await(5, TimeUnit.SECONDS);
        boolean smsComplete = smsLatch.await(5, TimeUnit.SECONDS);
        boolean auditComplete = auditLatch.await(5, TimeUnit.SECONDS);

        log.info("Consumer groups test completed. Email: {}, SMS: {}, Audit: {}",
            emailComplete, smsComplete, auditComplete);

        // Note: Resources will be cleaned up in cleanup() method
    }

    private void demonstrateDeadLetterQueue() throws Exception {
        log.info("=== Demonstrating Dead Letter Queue ===");

        String topic = "error-events";
        MessageProducer producer = factory.createProducer();
        MessageConsumer consumer = factory.createConsumer("error-processor");

        activeProducers.add(producer);
        activeConsumers.add(consumer);

        CountDownLatch errorLatch = new CountDownLatch(2);

        // Handler that will fail processing
        MessageHandler failingHandler = message -> {
            try {
                ErrorEvent event = objectMapper.readValue((String) message.getPayload(), ErrorEvent.class);
                log.info("Processing message that will fail: {}", event.getEventId());

                // Simulate processing error
                errorLatch.countDown();
                return MessageHandleResult.RETRY; // Will retry and eventually go to DLQ
            } catch (Exception e) {
                log.error("Failed to process message", e);
                return MessageHandleResult.FAIL;
            }
        };

        consumer.subscribe(topic, "error-group", failingHandler);
        consumer.start();

        // Produce messages that will fail
        for (int i = 1; i <= 2; i++) {
            ErrorEvent event = new ErrorEvent(
                "error-test-" + i,
                "This message will fail processing",
                Instant.now()
            );

            String payload = objectMapper.writeValueAsString(event);
            producer.send(topic, payload).get();
            log.info("Produced error-prone message: {}", event.getEventId());

            Thread.sleep(500);
        }

        // Wait for errors to be processed
        boolean errorsProcessed = errorLatch.await(10, TimeUnit.SECONDS);
        log.info("Dead letter queue test completed. Errors processed: {}", errorsProcessed);

        // Check dead letter queue size
        DeadLetterQueueManager dlqManager = new DeadLetterQueueManager(redissonClient);
        long dlqSize = dlqManager.getDeadLetterQueueSize(topic);
        log.info("Dead letter queue size for {}: {}", topic, dlqSize);

        // Note: Resources will be cleaned up in cleanup() method
    }

    private void demonstrateBatchProcessing() throws Exception {
        log.info("=== Demonstrating Batch Processing ===");

        String topic = "batch-events";
        MessageProducer producer = factory.createProducer();
        MessageConsumer consumer = factory.createConsumer("batch-processor");

        activeProducers.add(producer);
        activeConsumers.add(consumer);

        AtomicInteger processedCount = new AtomicInteger(0);
        CountDownLatch batchLatch = new CountDownLatch(20);

        // Batch handler
        MessageHandler batchHandler = message -> {
            try {
                BatchEvent event = objectMapper.readValue((String) message.getPayload(), BatchEvent.class);
                // Simulate batch processing
                Thread.sleep(10);
                int count = processedCount.incrementAndGet();

                if (count % 5 == 0) {
                    log.info("Processed {} batch items", count);
                }

                batchLatch.countDown();
                return MessageHandleResult.SUCCESS;
            } catch (Exception e) {
                log.error("Batch processing error", e);
                return MessageHandleResult.FAIL;
            }
        };

        consumer.subscribe(topic, "batch-group", batchHandler);
        consumer.start();

        // Produce messages rapidly for batch processing
        for (int i = 1; i <= 20; i++) {
            BatchEvent event = new BatchEvent(
                "batch-item-" + i,
                "Batch processing item " + i,
                i,
                Instant.now()
            );

            String payload = objectMapper.writeValueAsString(event);
            producer.send(topic, payload);

            if (i % 5 == 0) {
                log.info("Produced {} batch items", i);
            }

            Thread.sleep(50); // Fast production
        }

        // Wait for batch processing to complete
        boolean batchComplete = batchLatch.await(10, TimeUnit.SECONDS);
        log.info("Batch processing test completed. Success: {}, Total processed: {}",
            batchComplete, processedCount.get());

        // Note: Resources will be cleaned up in cleanup() method
    }

    private void performanceTest() throws Exception {
        log.info("=== Performance Test ===");

        String topic = "perf-events";
        MessageProducer producer = factory.createProducer();
        MessageConsumer consumer = factory.createConsumer("perf-consumer");

        activeProducers.add(producer);
        activeConsumers.add(consumer);

        int messageCount = 100; // Reduced from 1000 for faster demo
        CountDownLatch consumeLatch = new CountDownLatch(messageCount);

        long startTime = System.currentTimeMillis();

        // Simple fast handler
        MessageHandler perfHandler = message -> {
            consumeLatch.countDown();
            return MessageHandleResult.SUCCESS;
        };

        consumer.subscribe(topic, "perf-group", perfHandler);
        consumer.start();

        // Produce messages
        for (int i = 1; i <= messageCount; i++) {
            PerformanceEvent event = new PerformanceEvent(
                "perf-" + i,
                "Performance test message " + i,
                Instant.now()
            );

            String payload = objectMapper.writeValueAsString(event);
            producer.send(topic, payload);

            if (i % 20 == 0) {
                log.info("Produced {} messages", i);
            }
        }

        // Wait for completion
        boolean consumeComplete = consumeLatch.await(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        log.info("Performance test completed in {}ms", duration);
        log.info("Consumption complete: {}", consumeComplete);
        log.info("Throughput: {} messages/second", (messageCount * 1000.0) / duration);

        // Note: Resources will be cleaned up in cleanup() method
    }

    private MessageHandler createNotificationHandler(String type, CountDownLatch latch) {
        return message -> {
            try {
                NotificationEvent event = objectMapper.readValue((String) message.getPayload(), NotificationEvent.class);
                log.info("[{}] Processing notification: {} for user: {}",
                    type, event.getNotificationId(), event.getUserId());

                // Simulate processing time
                Thread.sleep(100);

                latch.countDown();
                return MessageHandleResult.SUCCESS;
            } catch (Exception e) {
                log.error("{} consumer error", type, e);
                return MessageHandleResult.FAIL;
            }
        };
    }

    private void cleanup() {
        log.info("Cleaning up resources...");

        // First stop all consumers to prevent them from trying to access Redis after shutdown
        log.info("Stopping {} active consumers...", activeConsumers.size());
        for (MessageConsumer consumer : activeConsumers) {
            try {
                consumer.stop();
                consumer.close();
            } catch (Exception e) {
                log.warn("Error stopping consumer", e);
            }
        }
        activeConsumers.clear();

        // Then close all producers
        log.info("Closing {} active producers...", activeProducers.size());
        for (MessageProducer producer : activeProducers) {
            try {
                producer.close();
            } catch (Exception e) {
                log.warn("Error closing producer", e);
            }
        }
        activeProducers.clear();

        // Finally shutdown Redisson client
        if (redissonClient != null) {
            try {
                Thread.sleep(200); // Give time for cleanup
                redissonClient.shutdown();
                log.info("Redisson client shutdown completed");
            } catch (Exception e) {
                log.warn("Error shutting down Redisson client", e);
            }
        }

        log.info("Cleanup completed");
    }

    // Event classes for demonstration
    @Data
    public static class OrderEvent {
        private String orderId;
        private String eventType;
        private Double amount;
        private String userId;
        private Instant timestamp;

        public OrderEvent() {}

        public OrderEvent(String orderId, String eventType, Double amount, String userId, Instant timestamp) {
            this.orderId = orderId;
            this.eventType = eventType;
            this.amount = amount;
            this.userId = userId;
            this.timestamp = timestamp;
        }
    }

    @Data
    public static class NotificationEvent {
        private String notificationId;
        private String message;
        private String userId;
        private List<String> channels;
        private Instant timestamp;

        public NotificationEvent() {}

        public NotificationEvent(String notificationId, String message, String userId,
                               List<String> channels, Instant timestamp) {
            this.notificationId = notificationId;
            this.message = message;
            this.userId = userId;
            this.channels = channels;
            this.timestamp = timestamp;
        }
    }

    @Data
    public static class ErrorEvent {
        private String eventId;
        private String description;
        private Instant timestamp;

        public ErrorEvent() {}

        public ErrorEvent(String eventId, String description, Instant timestamp) {
            this.eventId = eventId;
            this.description = description;
            this.timestamp = timestamp;
        }
    }

    @Data
    public static class BatchEvent {
        private String itemId;
        private String data;
        private Integer sequence;
        private Instant timestamp;

        public BatchEvent() {}

        public BatchEvent(String itemId, String data, Integer sequence, Instant timestamp) {
            this.itemId = itemId;
            this.data = data;
            this.sequence = sequence;
            this.timestamp = timestamp;
        }
    }

    @Data
    public static class PerformanceEvent {
        private String eventId;
        private String payload;
        private Instant timestamp;

        public PerformanceEvent() {}

        public PerformanceEvent(String eventId, String payload, Instant timestamp) {
            this.eventId = eventId;
            this.payload = payload;
            this.timestamp = timestamp;
        }
    }
}
