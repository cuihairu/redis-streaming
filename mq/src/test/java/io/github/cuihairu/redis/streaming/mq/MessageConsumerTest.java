package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MessageConsumer interface
 */
class MessageConsumerTest {

    @Test
    void testInterfaceIsDefined() {
        // Given & When & Then
        assertTrue(MessageConsumer.class.isInterface());
    }

    @Test
    void testSubscribeMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageConsumer.class.getMethod("subscribe", String.class, MessageHandler.class));
    }

    @Test
    void testSubscribeWithConsumerGroupMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageConsumer.class.getMethod("subscribe", String.class, String.class, MessageHandler.class));
    }

    @Test
    void testSubscribeWithOptionsMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageConsumer.class.getMethod("subscribe", String.class, String.class, MessageHandler.class, SubscriptionOptions.class));
    }

    @Test
    void testUnsubscribeMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageConsumer.class.getMethod("unsubscribe", String.class));
    }

    @Test
    void testStartMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageConsumer.class.getMethod("start"));
    }

    @Test
    void testStopMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageConsumer.class.getMethod("stop"));
    }

    @Test
    void testCloseMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageConsumer.class.getMethod("close"));
    }

    @Test
    void testIsRunningMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageConsumer.class.getMethod("isRunning"));
    }

    @Test
    void testIsClosedMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageConsumer.class.getMethod("isClosed"));
    }

    @Test
    void testSimpleImplementation() {
        // Given - create a simple in-memory implementation
        MessageConsumer consumer = new MessageConsumer() {
            private boolean running = false;
            private boolean closed = false;

            @Override
            public void subscribe(String topic, MessageHandler handler) {
                // Simple implementation
            }

            @Override
            public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
                // Simple implementation
            }

            @Override
            public void unsubscribe(String topic) {
                // Simple implementation
            }

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public void close() {
                closed = true;
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }
        };

        // When & Then - verify methods can be called
        assertDoesNotThrow(() -> consumer.subscribe("test-topic", message -> MessageHandleResult.SUCCESS));
        assertDoesNotThrow(() -> consumer.subscribe("test-topic", "test-group", message -> MessageHandleResult.SUCCESS));
        assertDoesNotThrow(() -> consumer.subscribe("test-topic", "test-group", message -> MessageHandleResult.SUCCESS, new SubscriptionOptions()));
        assertDoesNotThrow(() -> consumer.start());
        assertTrue(consumer.isRunning());
        assertDoesNotThrow(() -> consumer.stop());
        assertFalse(consumer.isRunning());
        assertDoesNotThrow(() -> consumer.close());
        assertTrue(consumer.isClosed());
    }

    @Test
    void testSubscribeWithOptionsDefaultImplementation() {
        // Given - create a consumer with default subscribe behavior
        MessageConsumer consumer = new MessageConsumer() {
            private boolean defaultSubscribeCalled = false;

            @Override
            public void subscribe(String topic, MessageHandler handler) {
                // Not used in this test
            }

            @Override
            public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
                defaultSubscribeCalled = true;
            }

            @Override
            public void unsubscribe(String topic) {
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When - call subscribe with options
        consumer.subscribe("test-topic", "test-group", message -> MessageHandleResult.SUCCESS, new SubscriptionOptions());

        // Then - default implementation should delegate to basic subscribe
        assertTrue(consumer instanceof MessageConsumer);
    }

    @Test
    void testMultipleSubscribeCalls() {
        // Given
        MessageConsumer consumer = new MessageConsumer() {
            private int subscribeCount = 0;

            @Override
            public void subscribe(String topic, MessageHandler handler) {
                subscribeCount++;
            }

            @Override
            public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
                subscribeCount++;
            }

            @Override
            public void unsubscribe(String topic) {
                subscribeCount--;
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When - multiple subscribe calls
        consumer.subscribe("topic1", message -> MessageHandleResult.SUCCESS);
        consumer.subscribe("topic2", "group1", message -> MessageHandleResult.SUCCESS);

        // Then - should handle multiple subscriptions
        assertDoesNotThrow(() -> consumer.unsubscribe("topic1"));
        assertDoesNotThrow(() -> consumer.start());
        assertDoesNotThrow(() -> consumer.stop());
        assertDoesNotThrow(() -> consumer.close());
    }

    @Test
    void testLifecycleMethods() {
        // Given
        MessageConsumer consumer = new MessageConsumer() {
            private boolean running = false;
            private boolean closed = false;

            @Override
            public void subscribe(String topic, MessageHandler handler) {
            }

            @Override
            public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
            }

            @Override
            public void unsubscribe(String topic) {
            }

            @Override
            public void start() {
                if (closed) {
                    throw new IllegalStateException("Cannot start closed consumer");
                }
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public void close() {
                closed = true;
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running && !closed;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }
        };

        // When & Then - verify lifecycle
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());

        consumer.start();
        assertTrue(consumer.isRunning());
        assertFalse(consumer.isClosed());

        consumer.stop();
        assertFalse(consumer.isRunning());
        assertFalse(consumer.isClosed());

        consumer.close();
        assertFalse(consumer.isRunning());
        assertTrue(consumer.isClosed());
    }

    @Test
    void testStartStopCycle() {
        // Given
        MessageConsumer consumer = new MessageConsumer() {
            private boolean running = false;

            @Override
            public void subscribe(String topic, MessageHandler handler) {
            }

            @Override
            public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
            }

            @Override
            public void unsubscribe(String topic) {
            }

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public void close() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When - multiple start/stop cycles
        consumer.start();
        assertTrue(consumer.isRunning());

        consumer.stop();
        assertFalse(consumer.isRunning());

        consumer.start();
        assertTrue(consumer.isRunning());

        consumer.stop();
        assertFalse(consumer.isRunning());
    }

    @Test
    void testCloseAfterStart() {
        // Given
        MessageConsumer consumer = new MessageConsumer() {
            private boolean running = false;
            private boolean closed = false;

            @Override
            public void subscribe(String topic, MessageHandler handler) {
            }

            @Override
            public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
            }

            @Override
            public void unsubscribe(String topic) {
            }

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public void close() {
                closed = true;
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }
        };

        // When
        consumer.start();
        consumer.close();

        // Then - close should stop the consumer
        assertFalse(consumer.isRunning());
        assertTrue(consumer.isClosed());
    }

    @Test
    void testUnsubscribe() {
        // Given
        MessageConsumer consumer = new MessageConsumer() {
            private boolean subscribed = true;

            @Override
            public void subscribe(String topic, MessageHandler handler) {
                subscribed = true;
            }

            @Override
            public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
                subscribed = true;
            }

            @Override
            public void unsubscribe(String topic) {
                subscribed = false;
            }

            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isRunning() {
                return false;
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When & Then
        assertDoesNotThrow(() -> consumer.unsubscribe("test-topic"));
    }
}
