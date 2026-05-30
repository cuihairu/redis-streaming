package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.DeadLetterEntry;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DlqConsumerAdapter
 */
@SuppressWarnings({"unchecked", "deprecation"})
class DlqConsumerAdapterTest {

    @Mock
    private RedissonClient mockRedissonClient;

    @Mock
    private RStream<Object, Object> mockStream;

    @Mock
    private StreamMessageId mockStreamMessageId;

    @Test
    void testConstructorWithValidParameters() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertNotNull(adapter);
        assertFalse(adapter.isRunning());
        assertFalse(adapter.isClosed());
    }

    @Test
    void testConstructorWithMqOptions() {
        MockitoAnnotations.openMocks(this);
        MqOptions options = MqOptions.builder()
                .defaultDlqGroup("custom-dlq-group")
                .streamKeyPrefix("custom:stream")
                .build();

        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", options);

        assertNotNull(adapter);
    }

    @Test
    void testConstructorWithNullOptions() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertNotNull(adapter);
        // Should use defaults: dlq-group and stream:topic
    }

    @Test
    void testConstructorWithNullClient_createsAdapter() {
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(null, "test-consumer", null);

        assertNotNull(adapter);
    }

    @Test
    void testSubscribeWithoutGroup() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testSubscribeWithGroup() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", "custom-group", handler));
    }

    @Test
    void testSubscribeWithNullHandler() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", (MessageHandler) null));
    }

    @Test
    void testSubscribeWithNullGroup() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", (String) null, handler));
    }

    @Test
    void testUnsubscribe() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        // Unsubscribe is a no-op as per implementation
        assertDoesNotThrow(() -> adapter.unsubscribe("test-topic"));
    }

    @Test
    void testUnsubscribeWithNullTopic() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertDoesNotThrow(() -> adapter.unsubscribe(null));
    }

    @Test
    void testStart() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertFalse(adapter.isRunning());
        adapter.start();
        assertTrue(adapter.isRunning());
    }

    @Test
    void testStop() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.start();
        assertTrue(adapter.isRunning());

        adapter.stop();
        assertFalse(adapter.isRunning());
    }

    @Test
    void testClose() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertFalse(adapter.isClosed());
        adapter.close();
        assertTrue(adapter.isClosed());
    }

    @Test
    void testStartStopIsIdempotent() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.start();
        adapter.start(); // Should not cause issues
        assertTrue(adapter.isRunning());

        adapter.stop();
        adapter.stop(); // Should not cause issues
        assertFalse(adapter.isRunning());
    }

    @Test
    void testCloseIsIdempotent() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.close();
        adapter.close(); // Should not cause issues
        assertTrue(adapter.isClosed());
    }

    @Test
    void testToResultWithSuccess() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        // The toResult method is private, but we can test indirectly through subscribe
        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testToResultWithRetry() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.RETRY;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testToResultWithFail() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.FAIL;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testToResultWithDeadLetter() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.DEAD_LETTER;

        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testSubscribeWithMultipleTopics() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> {
            adapter.subscribe("topic1", handler);
            adapter.subscribe("topic2", handler);
            adapter.subscribe("topic3", handler);
        });
    }

    @Test
    void testLifecycleOrder() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        // Test proper lifecycle order
        assertFalse(adapter.isRunning());
        assertFalse(adapter.isClosed());

        adapter.start();
        assertTrue(adapter.isRunning());
        assertFalse(adapter.isClosed());

        adapter.stop();
        assertFalse(adapter.isRunning());
        assertFalse(adapter.isClosed());

        adapter.close();
        assertFalse(adapter.isRunning());
        assertTrue(adapter.isClosed());
    }

    @Test
    void testSubscribeWhenAlreadyRunning() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.start();
        assertTrue(adapter.isRunning());

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;
        assertDoesNotThrow(() -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testSubscribeWithEmptyTopic() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> adapter.subscribe("", handler));
    }

    @Test
    void testSubscribeWithWhitespaceTopic() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        assertDoesNotThrow(() -> adapter.subscribe("   ", handler));
    }

    @Test
    void testStartWhenAlreadyClosed() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.close();
        assertTrue(adapter.isClosed());

        // Starting after close may throw RejectedExecutionException
        assertThrows(Exception.class, () -> adapter.start());
    }

    @Test
    void testStopWhenNotStarted() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        assertFalse(adapter.isRunning());

        assertDoesNotThrow(() -> adapter.stop());
        assertFalse(adapter.isRunning());
    }

    @Test
    void testMultipleCloseCalls() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.close();
        adapter.close();
        adapter.close();

        assertTrue(adapter.isClosed());
    }

    @Test
    void testSubscribeAfterClose() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.close();
        assertTrue(adapter.isClosed());

        MessageHandler handler = message -> MessageHandleResult.SUCCESS;
        // Subscribing after close may throw IllegalStateException
        assertThrows(Exception.class, () -> adapter.subscribe("test-topic", handler));
    }

    @Test
    void testConstructorWithEmptyConsumerName() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "", null);

        assertNotNull(adapter);
    }

    @Test
    void testConstructorWithNullConsumerName() {
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, null, null);

        assertNotNull(adapter);
    }

    @Test
    void testStartStopStartCycle() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.start();
        assertTrue(adapter.isRunning());

        adapter.stop();
        assertFalse(adapter.isRunning());

        adapter.start();
        assertTrue(adapter.isRunning());
    }

    @Test
    void testCloseStopsRunningState() {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        adapter.start();
        assertTrue(adapter.isRunning());

        adapter.close();
        assertFalse(adapter.isRunning());
        assertTrue(adapter.isClosed());
    }

    // ==========================================
    // Private Method Tests (using reflection)
    // ==========================================

    @Test
    void testToResultWithSuccessResult() throws Exception {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        DeadLetterEntry entry = createTestEntry();
        MessageHandler handler = message -> MessageHandleResult.SUCCESS;

        Object result = toResultMethod.invoke(adapter, handler, entry);

        assertNotNull(result);
        // Should return HandleResult.SUCCESS enum
        assertEquals("SUCCESS", result.toString());
    }

    @Test
    void testToResultWithRetryResult() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(mockRedissonClient.getStream(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockStream);
        when(mockStream.add(any())).thenReturn(mockStreamMessageId);
        when(mockStream.size()).thenReturn(1L);
        when(mockStream.isExists()).thenReturn(true);

        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        DeadLetterEntry entry = createTestEntry();
        MessageHandler handler = message -> MessageHandleResult.RETRY;

        Object result = toResultMethod.invoke(adapter, handler, entry);

        assertNotNull(result);
        assertEquals("RETRY", result.toString());
        // Verify Redis stream was called for retry
        verify(mockRedissonClient).getStream(anyString(), eq(StringCodec.INSTANCE));
        verify(mockStream).add(any());
    }

    @Test
    void testToResultWithFailResult() throws Exception {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        DeadLetterEntry entry = createTestEntry();
        MessageHandler handler = message -> MessageHandleResult.FAIL;

        Object result = toResultMethod.invoke(adapter, handler, entry);

        assertNotNull(result);
        assertEquals("FAIL", result.toString());
    }

    @Test
    void testToResultWithDeadLetterResult() throws Exception {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        DeadLetterEntry entry = createTestEntry();
        MessageHandler handler = message -> MessageHandleResult.DEAD_LETTER;

        Object result = toResultMethod.invoke(adapter, handler, entry);

        assertNotNull(result);
        assertEquals("FAIL", result.toString()); // DEAD_LETTER maps to FAIL
    }

    @Test
    void testToResultWithRetryAndRedisFailure() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(mockRedissonClient.getStream(anyString(), eq(StringCodec.INSTANCE))).thenThrow(new RuntimeException("Redis error"));

        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        DeadLetterEntry entry = createTestEntry();
        MessageHandler handler = message -> MessageHandleResult.RETRY;

        Object result = toResultMethod.invoke(adapter, handler, entry);

        assertNotNull(result);
        assertEquals("RETRY", result.toString());
        // Should still return RETRY even if Redis operation fails
    }

    @Test
    void testToResultWithNullTimestamp() throws Exception {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        // Create entry with null timestamp
        DeadLetterEntry entry = new DeadLetterEntry("test-id", "test-topic", 0, "test-payload",
                Map.of("key1", "value1"), null, 3, 5);

        MessageHandler handler = message -> {
            // Verify timestamp was set to current time when null
            assertNotNull(message.getTimestamp());
            return MessageHandleResult.SUCCESS;
        };

        Object result = toResultMethod.invoke(adapter, handler, entry);

        assertNotNull(result);
        assertEquals("SUCCESS", result.toString());
    }

    @Test
    void testToResultWithEmptyHeaders() throws Exception {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        // Create entry with empty headers
        DeadLetterEntry entry = new DeadLetterEntry("test-id", "test-topic", 0, "test-payload",
                new HashMap<>(), Instant.parse("2024-01-01T00:00:00Z"), 3, 5);

        MessageHandler handler = message -> {
            // Headers should still contain partitionId
            assertNotNull(message.getHeaders());
            assertTrue(message.getHeaders().containsKey("partitionId"));
            return MessageHandleResult.SUCCESS;
        };

        Object result = toResultMethod.invoke(adapter, handler, entry);

        assertNotNull(result);
        assertEquals("SUCCESS", result.toString());
    }

    @Test
    void testToResultWithNullHeaders() throws Exception {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        // Create entry with null headers
        DeadLetterEntry entry = new DeadLetterEntry("test-id", "test-topic", 0, "test-payload",
                null, Instant.parse("2024-01-01T00:00:00Z"), 3, 5);

        MessageHandler handler = message -> {
            // Headers should still be created with partitionId
            assertNotNull(message.getHeaders());
            assertTrue(message.getHeaders().containsKey("partitionId"));
            return MessageHandleResult.SUCCESS;
        };

        Object result = toResultMethod.invoke(adapter, handler, entry);

        assertNotNull(result);
        assertEquals("SUCCESS", result.toString());
    }

    @Test
    void testToJsonWithString() throws Exception {
        Method toJsonMethod = DlqConsumerAdapter.class.getDeclaredMethod("toJson", Object.class);
        toJsonMethod.setAccessible(true);

        String result = (String) toJsonMethod.invoke(null, "test-string");

        assertEquals("\"test-string\"", result);
    }

    @Test
    void testToJsonWithMap() throws Exception {
        Method toJsonMethod = DlqConsumerAdapter.class.getDeclaredMethod("toJson", Object.class);
        toJsonMethod.setAccessible(true);

        Map<String, Object> map = new HashMap<>();
        map.put("key1", "value1");
        map.put("key2", 123);

        String result = (String) toJsonMethod.invoke(null, map);

        assertNotNull(result);
        assertTrue(result.contains("key1"));
        assertTrue(result.contains("value1"));
    }

    @Test
    void testToJsonWithObject() throws Exception {
        Method toJsonMethod = DlqConsumerAdapter.class.getDeclaredMethod("toJson", Object.class);
        toJsonMethod.setAccessible(true);

        TestObject obj = new TestObject("test", 42);

        String result = (String) toJsonMethod.invoke(null, obj);

        assertNotNull(result);
        assertTrue(result.contains("test"));
    }

    @Test
    void testToJsonWithJsonSerializable() throws Exception {
        Method toJsonMethod = DlqConsumerAdapter.class.getDeclaredMethod("toJson", Object.class);
        toJsonMethod.setAccessible(true);

        Object obj = new Object() {
            @Override
            public String toString() {
                return "custom-toString";
            }
        };

        String result = (String) toJsonMethod.invoke(null, obj);

        // If JSON serialization fails, should fall back to toString
        assertNotNull(result);
    }

    @Test
    void testToResultWithRetryAndNonStringPayload() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(mockRedissonClient.getStream(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockStream);
        when(mockStream.add(any())).thenReturn(mockStreamMessageId);

        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        // Create entry with non-string payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "value");
        DeadLetterEntry entry = new DeadLetterEntry("test-id", "test-topic", 0, payload,
                Map.of("key1", "value1"), Instant.parse("2024-01-01T00:00:00Z"), 3, 5);

        MessageHandler handler = message -> MessageHandleResult.RETRY;

        Object result = toResultMethod.invoke(adapter, handler, entry);

        assertNotNull(result);
        assertEquals("RETRY", result.toString());
        verify(mockStream).add(any());
    }

    @Test
    void testToResultWithHandlerThrowingException() throws Exception {
        MockitoAnnotations.openMocks(this);
        DlqConsumerAdapter adapter = new DlqConsumerAdapter(mockRedissonClient, "test-consumer", null);

        Method toResultMethod = DlqConsumerAdapter.class.getDeclaredMethod("toResult", MessageHandler.class, DeadLetterEntry.class);
        toResultMethod.setAccessible(true);

        DeadLetterEntry entry = createTestEntry();
        MessageHandler handler = message -> {
            throw new RuntimeException("Handler exception");
        };

        assertThrows(Exception.class, () -> toResultMethod.invoke(adapter, handler, entry));
    }

    // ==========================================
    // Test Helper Methods
    // ==========================================

    private DeadLetterEntry createTestEntry() {
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        headers.put("key2", "value2");

        return new DeadLetterEntry("test-id", "test-topic", 0, "test-payload",
                headers, Instant.parse("2024-01-01T00:00:00Z"), 3, 5);
    }

    // Test class for JSON serialization
    static class TestObject {
        private String name;
        private int value;

        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() { return name; }
        public int getValue() { return value; }
    }
}
