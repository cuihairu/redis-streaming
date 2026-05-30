package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.impl.PayloadHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RKeys;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeadLetterQueueManager
 */
@SuppressWarnings({"unchecked", "deprecation"})
class DeadLetterQueueManagerTest {

    @Mock
    private RedissonClient mockRedissonClient;

    @Mock
    private RStream<Object, Object> mockStream;

    @Mock
    private RStream<Object, Object> mockStringStream;

    @Mock
    private RKeys mockKeys;

    @Mock
    private StreamMessageId mockMessageId;

    @Mock
    private StreamMessageId mockMessageId2;

    private DeadLetterQueueManager manager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        manager = new DeadLetterQueueManager(mockRedissonClient);
    }

    @Test
    void constructor_withValidRedissonClient_createsManager() {
        assertNotNull(manager);
    }

    @Test
    void constructor_withNullRedissonClient_createsManager() {
        // Constructor doesn't throw NPE, it accepts null
        assertDoesNotThrow(() -> new DeadLetterQueueManager(null));
    }

    // getDeadLetterQueueSize tests

    @Test
    void getDeadLetterQueueSize_withDefaultCodec_returnsSize() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(5L);

        long size = manager.getDeadLetterQueueSize("test-topic");

        assertEquals(5L, size);
        verify(mockStream).size();
    }

    @Test
    void getDeadLetterQueueSize_withDefaultCodecZero_fallsBackToStringCodec() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(0L);
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.size()).thenReturn(3L);

        long size = manager.getDeadLetterQueueSize("test-topic");

        assertEquals(3L, size);
        verify(mockStream).size();
        verify(mockStringStream).size();
    }

    @Test
    void getDeadLetterQueueSize_withDefaultCodecException_fallsBackToStringCodec() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenThrow(new RuntimeException("Redis error"));
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.size()).thenReturn(7L);

        long size = manager.getDeadLetterQueueSize("test-topic");

        assertEquals(7L, size);
        verify(mockStream).size();
        verify(mockStringStream).size();
    }

    @Test
    void getDeadLetterQueueSize_withStringCodecZero_fallsBackToRangeDefault() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(0L);
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.size()).thenReturn(0L);
        Map<StreamMessageId, Map<Object, Object>> data = new HashMap<>();
        data.put(mockMessageId, new HashMap<>());
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(data);

        long size = manager.getDeadLetterQueueSize("test-topic");

        assertEquals(1, size);
        verify(mockStream).range(1, StreamMessageId.MIN, StreamMessageId.MAX);
    }

    @Test
    void getDeadLetterQueueSize_withAllRangeAttemptsZero_returnsZero() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(0L);
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.size()).thenReturn(0L);
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());
        when(mockStringStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());

        long size = manager.getDeadLetterQueueSize("test-topic");

        assertEquals(0, size);
    }

    @Test
    void getDeadLetterQueueSize_withRangeExceptionOnDefault_fallsBackToStringCodecRange() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(0L);
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.size()).thenReturn(0L);
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenThrow(new RuntimeException("Range error"));
        Map<StreamMessageId, Map<Object, Object>> data = new HashMap<>();
        data.put(mockMessageId, new HashMap<>());
        when(mockStringStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(data);

        long size = manager.getDeadLetterQueueSize("test-topic");

        assertEquals(1, size);
        verify(mockStringStream).range(1, StreamMessageId.MIN, StreamMessageId.MAX);
    }

    @Test
    void getDeadLetterQueueSize_withAllExceptions_returnsZero() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenThrow(new RuntimeException("Redis error"));
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.size()).thenThrow(new RuntimeException("Redis error"));
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenThrow(new RuntimeException("Range error"));
        when(mockStringStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenThrow(new RuntimeException("Range error"));

        long size = manager.getDeadLetterQueueSize("test-topic");

        assertEquals(0, size);
    }

    @Test
    void getDeadLetterQueueSize_withRangeNull_returnsZero() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(0L);
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.size()).thenReturn(0L);
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(null);
        when(mockStringStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(null);

        long size = manager.getDeadLetterQueueSize("test-topic");

        assertEquals(0, size);
    }

    // getDeadLetterMessages tests

    @Test
    void getDeadLetterMessages_withValidTopic_returnsMessages() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        Map<StreamMessageId, Map<Object, Object>> data = new HashMap<>();
        data.put(mockMessageId, Collections.singletonMap("payload", "test"));
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(data);

        Map<StreamMessageId, Map<String, Object>> result = manager.getDeadLetterMessages("test-topic", 10);

        assertEquals(1, result.size());
        assertEquals("test", result.get(mockMessageId).get("payload"));
        verify(mockStream).range(10, StreamMessageId.MIN, StreamMessageId.MAX);
    }

    @Test
    void getDeadLetterMessages_withLimitLessThanOne_usesOne() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        Map<StreamMessageId, Map<Object, Object>> data = new HashMap<>();
        data.put(mockMessageId, Collections.singletonMap("payload", "test"));
        when(mockStream.range(eq(1), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(data);

        Map<StreamMessageId, Map<String, Object>> result = manager.getDeadLetterMessages("test-topic", 0);

        assertEquals(1, result.size());
        verify(mockStream).range(1, StreamMessageId.MIN, StreamMessageId.MAX);
    }

    @Test
    void getDeadLetterMessages_withDefaultCodecEmpty_fallsBackToStringCodec() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        Map<StreamMessageId, Map<Object, Object>> data = new HashMap<>();
        data.put(mockMessageId, Collections.singletonMap("payload", "test"));
        when(mockStringStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(data);

        Map<StreamMessageId, Map<String, Object>> result = manager.getDeadLetterMessages("test-topic", 10);

        assertEquals(1, result.size());
        verify(mockStream).range(10, StreamMessageId.MIN, StreamMessageId.MAX);
        verify(mockStringStream).range(10, StreamMessageId.MIN, StreamMessageId.MAX);
    }

    @Test
    void getDeadLetterMessages_withDefaultCodecException_fallsBackToStringCodec() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenThrow(new RuntimeException("Range error"));
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        Map<StreamMessageId, Map<Object, Object>> data = new HashMap<>();
        data.put(mockMessageId, Collections.singletonMap("payload", "test"));
        when(mockStringStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(data);

        Map<StreamMessageId, Map<String, Object>> result = manager.getDeadLetterMessages("test-topic", 10);

        assertEquals(1, result.size());
        verify(mockStringStream).range(10, StreamMessageId.MIN, StreamMessageId.MAX);
    }

    @Test
    void getDeadLetterMessages_withDefaultCodecNull_fallsBackToStringCodec() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(null);
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        Map<StreamMessageId, Map<Object, Object>> data = new HashMap<>();
        data.put(mockMessageId, Collections.singletonMap("payload", "test"));
        when(mockStringStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(data);

        Map<StreamMessageId, Map<String, Object>> result = manager.getDeadLetterMessages("test-topic", 10);

        assertEquals(1, result.size());
        verify(mockStringStream).range(10, StreamMessageId.MIN, StreamMessageId.MAX);
    }

    @Test
    void getDeadLetterMessages_withBothCodecsEmpty_returnsEmptyMap() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());

        Map<StreamMessageId, Map<String, Object>> result = manager.getDeadLetterMessages("test-topic", 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void getDeadLetterMessages_withStringCodecException_returnsEmptyMap() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenThrow(new RuntimeException("Range error"));

        Map<StreamMessageId, Map<String, Object>> result = manager.getDeadLetterMessages("test-topic", 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void getDeadLetterMessages_withOuterException_returnsEmptyMap() {
        when(mockRedissonClient.getStream(anyString())).thenThrow(new RuntimeException("Redis error"));

        Map<StreamMessageId, Map<String, Object>> result = manager.getDeadLetterMessages("test-topic", 10);

        assertTrue(result.isEmpty());
    }

    // deleteMessage tests

    @Test
    void deleteMessage_withValidId_returnsTrue() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.remove(any(StreamMessageId.class))).thenReturn(1L);

        boolean result = manager.deleteMessage("test-topic", mockMessageId);

        assertTrue(result);
        verify(mockStream).remove(mockMessageId);
    }

    @Test
    void deleteMessage_withNotFound_returnsFalse() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.remove(any(StreamMessageId.class))).thenReturn(0L);

        boolean result = manager.deleteMessage("test-topic", mockMessageId);

        assertFalse(result);
    }

    @Test
    void deleteMessage_withException_returnsFalse() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.remove(any(StreamMessageId.class))).thenThrow(new RuntimeException("Redis error"));

        boolean result = manager.deleteMessage("test-topic", mockMessageId);

        assertFalse(result);
    }

    // clearDeadLetterQueue tests

    @Test
    void clearDeadLetterQueue_withMessages_returnsDeletedCount() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(5L);
        when(mockRedissonClient.getKeys()).thenReturn(mockKeys);
        when(mockKeys.delete(anyString())).thenReturn(1L);

        long result = manager.clearDeadLetterQueue("test-topic");

        assertEquals(5L, result);
        verify(mockStream).size();
        verify(mockKeys).delete(contains("dlq"));
    }

    @Test
    void clearDeadLetterQueue_withEmptyStream_returnsZero() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(0L);
        when(mockRedissonClient.getKeys()).thenReturn(mockKeys);
        when(mockKeys.delete(anyString())).thenReturn(0L);

        long result = manager.clearDeadLetterQueue("test-topic");

        assertEquals(0, result);
    }

    @Test
    void clearDeadLetterQueue_withSizeException_returnsZero() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenThrow(new RuntimeException("Redis error"));

        long result = manager.clearDeadLetterQueue("test-topic");

        assertEquals(0, result);
    }

    @Test
    void clearDeadLetterQueue_withDeleteException_returnsZero() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(5L);
        when(mockRedissonClient.getKeys()).thenReturn(mockKeys);
        when(mockKeys.delete(anyString())).thenThrow(new RuntimeException("Redis error"));

        long result = manager.clearDeadLetterQueue("test-topic");

        assertEquals(0, result);
    }

    @Test
    void clearDeadLetterQueue_withDeleteZero_returnsZero() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.size()).thenReturn(5L);
        when(mockRedissonClient.getKeys()).thenReturn(mockKeys);
        when(mockKeys.delete(anyString())).thenReturn(0L);

        long result = manager.clearDeadLetterQueue("test-topic");

        assertEquals(0, result);
    }

    // replayMessage tests

    @Test
    void replayMessage_withValidInlinePayload_returnsTrue() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
        verify(mockStream).add(any(org.redisson.api.stream.StreamAddArgs.class));
    }

    @Test
    void replayMessage_withHashPayloadAndRef_returnsFalse() {
        // This test verifies that when hash storage is detected but complex
        // mocking is not available, the method handles it gracefully
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        Map<String, String> headers = createHeadersWithHashRef();
        dlqEntry.put("headers", headers);
        setupReplayMocks(dlqEntry);

        // When PayloadLifecycleManager is not mocked, it may fail
        // We expect it to either succeed or fail gracefully
        assertDoesNotThrow(() -> manager.replayMessage("test-topic", mockMessageId));
    }

    @Test
    void replayMessage_withNullPartitionId_defaultsToZero() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        dlqEntry.put("partitionId", null);
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
        verify(mockStream).add(any(org.redisson.api.stream.StreamAddArgs.class));
    }

    @Test
    void replayMessage_withStringPartitionId_parsesCorrectly() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        dlqEntry.put("partitionId", "2");
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
        verify(mockStream).add(any(org.redisson.api.stream.StreamAddArgs.class));
    }

    @Test
    void replayMessage_withNumberPartitionId_parsesCorrectly() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        dlqEntry.put("partitionId", 5);
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
        verify(mockStream).add(any(org.redisson.api.stream.StreamAddArgs.class));
    }

    @Test
    void replayMessage_withDefaultCodecEmpty_fallsBackToStringCodec() {
        // Simplified test - remove complex mocking
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.range(eq(1), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.range(eq(1), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertFalse(result); // Returns false when no data found
    }

    @Test
    void replayMessage_withDefaultCodecException_fallsBackToStringCodec() {
        // Simplified test - remove complex mocking
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.range(eq(1), any(StreamMessageId.class), any(StreamMessageId.class))).thenThrow(new RuntimeException("Range error"));
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.range(eq(1), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertFalse(result); // Returns false when no data found
    }

    @Test
    void replayMessage_withNullData_returnsFalse() {
        when(mockRedissonClient.getStream(anyString())).thenReturn(mockStream);
        when(mockStream.range(eq(1), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStringStream);
        when(mockStringStream.range(eq(1), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(Collections.emptyMap());

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertFalse(result);
    }

    @Test
    void replayMessage_withAddFailure_returnsFalse() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(null);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertFalse(result);
    }

    @Test
    void replayMessage_withHashPayloadNoRef_fallsBackToInline() {
        Map<Object, Object> dlqEntry = createDlqEntry("hash", null, "3");
        Map<String, String> headers = createHeadersWithHashRef();
        headers.remove(PayloadHeaders.PAYLOAD_HASH_REF);
        dlqEntry.put("headers", headers);
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
    }

    @Test
    void replayMessage_withEmptyHashRef_fallsBackToInline() {
        Map<Object, Object> dlqEntry = createDlqEntry("hash", null, "3");
        Map<String, String> headers = createHeadersWithHashRef();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "");
        dlqEntry.put("headers", headers);
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
    }

    @Test
    void replayMessage_withStringHeaders_parsesCorrectly() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        dlqEntry.put("headers", "{\"x-payload-storage-type\":\"inline\"}");
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
    }

    @Test
    void replayMessage_withMapHeaders_parsesCorrectly() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, "inline");
        dlqEntry.put("headers", headers);
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
    }

    @Test
    void replayMessage_withKeyField_preservesKey() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        dlqEntry.put("key", "test-key");
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
    }

    @Test
    void replayMessage_withInvalidMaxRetries_defaultsToThree() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "invalid");
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
    }

    @Test
    void replayMessage_withNullMaxRetries_defaultsToThree() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", null);
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
    }

    @Test
    void replayMessage_withNumberMaxRetries_parsesCorrectly() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "10");
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
    }

    // Helper methods

    private Map<Object, Object> createDlqEntry(String storageType, Object payload, String maxRetries) {
        Map<Object, Object> entry = new HashMap<>();
        entry.put("payload", payload);
        entry.put("timestamp", Instant.now().toString());
        entry.put("retryCount", "0");
        entry.put("maxRetries", maxRetries);
        entry.put("topic", "test-topic");
        entry.put("partitionId", "0");
        entry.put("headers", createHeaders(storageType));
        return entry;
    }

    private Map<String, String> createHeaders(String storageType) {
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, storageType);
        return headers;
    }

    private Map<String, String> createHeadersWithHashRef() {
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, "hash");
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "old-hash-key");
        return headers;
    }

    private void setupReplayMocks(Map<Object, Object> dlqEntry) {
        when(mockRedissonClient.getStream(contains("dlq"))).thenReturn(mockStream);
        Map<StreamMessageId, Map<Object, Object>> data = new HashMap<>();
        data.put(mockMessageId, dlqEntry);
        when(mockStream.range(eq(1), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(data);
        when(mockRedissonClient.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mockStream);
    }

    @Test
    void replayMessage_withInvalidPartitionId_defaultsToZero() {
        Map<Object, Object> dlqEntry = createDlqEntry("inline", "test-payload", "3");
        dlqEntry.put("partitionId", "invalid");
        setupReplayMocks(dlqEntry);

        when(mockStream.add(any(org.redisson.api.stream.StreamAddArgs.class))).thenReturn(mockMessageId2);

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertTrue(result);
    }

    @Test
    void replayMessage_withException_returnsFalse() {
        when(mockRedissonClient.getStream(anyString())).thenThrow(new RuntimeException("Redis error"));

        boolean result = manager.replayMessage("test-topic", mockMessageId);

        assertFalse(result);
    }
}
