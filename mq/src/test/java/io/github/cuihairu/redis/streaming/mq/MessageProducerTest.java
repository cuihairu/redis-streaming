package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MessageProducer interface
 */
class MessageProducerTest {

    @Test
    void testInterfaceIsDefined() {
        // Given & When & Then
        assertTrue(MessageProducer.class.isInterface());
    }

    @Test
    void testSendMessageMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageProducer.class.getMethod("send", Message.class));
    }

    @Test
    void testSendWithKeyMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageProducer.class.getMethod("send", String.class, String.class, Object.class));
    }

    @Test
    void testSendSimpleMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageProducer.class.getMethod("send", String.class, Object.class));
    }

    @Test
    void testCloseMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageProducer.class.getMethod("close"));
    }

    @Test
    void testIsClosedMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MessageProducer.class.getMethod("isClosed"));
    }

    @Test
    void testSimpleImplementation() {
        // Given - create a simple in-memory implementation
        MessageProducer producer = new MessageProducer() {
            private boolean closed = false;

            @Override
            public CompletableFuture<String> send(Message message) {
                if (closed) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
                }
                return CompletableFuture.completedFuture("msg-id-1");
            }

            @Override
            public CompletableFuture<String> send(String topic, String key, Object payload) {
                if (closed) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
                }
                return CompletableFuture.completedFuture("msg-id-2");
            }

            @Override
            public CompletableFuture<String> send(String topic, Object payload) {
                if (closed) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
                }
                return CompletableFuture.completedFuture("msg-id-3");
            }

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }
        };

        // When & Then - verify methods can be called
        assertDoesNotThrow(() -> producer.send(new Message()));
        assertDoesNotThrow(() -> producer.send("test-topic", "key1", "payload"));
        assertDoesNotThrow(() -> producer.send("test-topic", "payload"));
        assertFalse(producer.isClosed());
        assertDoesNotThrow(() -> producer.close());
        assertTrue(producer.isClosed());
    }

    @Test
    void testSendCompletesSuccessfully() throws Exception {
        // Given
        MessageProducer producer = new MessageProducer() {
            @Override
            public CompletableFuture<String> send(Message message) {
                return CompletableFuture.completedFuture("msg-id-123");
            }

            @Override
            public CompletableFuture<String> send(String topic, String key, Object payload) {
                return CompletableFuture.completedFuture("msg-id-456");
            }

            @Override
            public CompletableFuture<String> send(String topic, Object payload) {
                return CompletableFuture.completedFuture("msg-id-789");
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When
        CompletableFuture<String> future1 = producer.send(new Message());
        CompletableFuture<String> future2 = producer.send("topic", "key", "payload");
        CompletableFuture<String> future3 = producer.send("topic", "payload");

        // Then
        assertEquals("msg-id-123", future1.get());
        assertEquals("msg-id-456", future2.get());
        assertEquals("msg-id-789", future3.get());
    }

    @Test
    void testSendFailsWhenClosed() {
        // Given
        MessageProducer producer = new MessageProducer() {
            private boolean closed = false;

            @Override
            public CompletableFuture<String> send(Message message) {
                if (closed) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
                }
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public CompletableFuture<String> send(String topic, String key, Object payload) {
                if (closed) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
                }
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public CompletableFuture<String> send(String topic, Object payload) {
                if (closed) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Producer is closed"));
                }
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }
        };

        // When
        producer.close();

        // Then
        CompletableFuture<String> future1 = producer.send(new Message());
        CompletableFuture<String> future2 = producer.send("topic", "key", "payload");
        CompletableFuture<String> future3 = producer.send("topic", "payload");

        assertTrue(future1.isCompletedExceptionally());
        assertTrue(future2.isCompletedExceptionally());
        assertTrue(future3.isCompletedExceptionally());
    }

    @Test
    void testMultipleSendCalls() throws Exception {
        // Given
        MessageProducer producer = new MessageProducer() {
            private int counter = 0;

            @Override
            public CompletableFuture<String> send(Message message) {
                return CompletableFuture.completedFuture("msg-" + (++counter));
            }

            @Override
            public CompletableFuture<String> send(String topic, String key, Object payload) {
                return CompletableFuture.completedFuture("msg-" + (++counter));
            }

            @Override
            public CompletableFuture<String> send(String topic, Object payload) {
                return CompletableFuture.completedFuture("msg-" + (++counter));
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When
        CompletableFuture<String> id1 = producer.send(new Message());
        CompletableFuture<String> id2 = producer.send(new Message());
        CompletableFuture<String> id3 = producer.send("topic", "key", "payload");

        // Then
        assertNotEquals(id1.get(), id2.get());
        assertNotEquals(id2.get(), id3.get());
    }

    @Test
    void testClose() {
        // Given
        MessageProducer producer = new MessageProducer() {
            private boolean closed = false;

            @Override
            public CompletableFuture<String> send(Message message) {
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public CompletableFuture<String> send(String topic, String key, Object payload) {
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public CompletableFuture<String> send(String topic, Object payload) {
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }
        };

        // When
        assertFalse(producer.isClosed());
        producer.close();

        // Then
        assertTrue(producer.isClosed());
    }

    @Test
    void testSendWithDifferentPayloadTypes() throws Exception {
        // Given
        MessageProducer producer = new MessageProducer() {
            @Override
            public CompletableFuture<String> send(Message message) {
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public CompletableFuture<String> send(String topic, String key, Object payload) {
                return CompletableFuture.completedFuture("msg-id-" + payload.getClass().getSimpleName());
            }

            @Override
            public CompletableFuture<String> send(String topic, Object payload) {
                return CompletableFuture.completedFuture("msg-id-" + payload.getClass().getSimpleName());
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When & Then
        assertEquals("msg-id-String", producer.send("topic", "key", "text").get());
        assertEquals("msg-id-Integer", producer.send("topic", "key", 123).get());
        assertEquals("msg-id-Double", producer.send("topic", 45.67).get());
    }

    @Test
    void testAsyncSend() throws Exception {
        // Given - producer that simulates async behavior
        MessageProducer producer = new MessageProducer() {
            @Override
            public CompletableFuture<String> send(Message message) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "async-msg-id";
                });
            }

            @Override
            public CompletableFuture<String> send(String topic, String key, Object payload) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "async-msg-id";
                });
            }

            @Override
            public CompletableFuture<String> send(String topic, Object payload) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "async-msg-id";
                });
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When
        CompletableFuture<String> future = producer.send(new Message());

        // Then - should complete asynchronously
        assertEquals("async-msg-id", future.get());
    }

    @Test
    void testSendWithNullKey() throws Exception {
        // Given
        MessageProducer producer = new MessageProducer() {
            @Override
            public CompletableFuture<String> send(Message message) {
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public CompletableFuture<String> send(String topic, String key, Object payload) {
                // Should handle null key
                return CompletableFuture.completedFuture("msg-id-with-null-key");
            }

            @Override
            public CompletableFuture<String> send(String topic, Object payload) {
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When & Then - should handle null key gracefully
        assertEquals("msg-id-with-null-key", producer.send("topic", null, "payload").get());
    }

    @Test
    void testSendWithEmptyPayload() throws Exception {
        // Given
        MessageProducer producer = new MessageProducer() {
            @Override
            public CompletableFuture<String> send(Message message) {
                return CompletableFuture.completedFuture("msg-id");
            }

            @Override
            public CompletableFuture<String> send(String topic, String key, Object payload) {
                return CompletableFuture.completedFuture("msg-id-empty");
            }

            @Override
            public CompletableFuture<String> send(String topic, Object payload) {
                return CompletableFuture.completedFuture("msg-id-empty");
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        };

        // When & Then
        assertEquals("msg-id-empty", producer.send("topic", "", "").get());
        assertEquals("msg-id-empty", producer.send("topic", "key", "").get());
    }
}
