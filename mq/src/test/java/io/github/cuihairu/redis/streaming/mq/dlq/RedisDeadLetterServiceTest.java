package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.redisson.api.StreamMessageId.*;

/**
 * Unit tests for RedisDeadLetterService.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisDeadLetterServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RStream<String, Object> rStream;

    @Mock
    private RBucket<String> rBucket;

    @Mock
    private RKeys rKeys;

    @Mock
    private ReplayHandler replayHandler;

    private RedisDeadLetterService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        lenient().when(redissonClient.getStream(anyString())).thenReturn((RStream) rStream);
        lenient().when(redissonClient.getBucket(anyString(), any(StringCodec.class))).thenReturn((RBucket) rBucket);
        lenient().when(redissonClient.getKeys()).thenReturn(rKeys);
    }

    // ==================== Constructor Tests ====================

    @Test
    void constructor_withRedissonClient_createsService() {
        // When
        RedisDeadLetterService service = new RedisDeadLetterService(redissonClient);

        // Then
        assertNotNull(service);
    }

    @Test
    void constructor_withRedissonClientAndReplayHandler_createsService() {
        // When
        RedisDeadLetterService service = new RedisDeadLetterService(redissonClient, replayHandler);

        // Then
        assertNotNull(service);
    }

    // ==================== send() Tests ====================

    @Test
    void send_withValidRecord_addsToStream() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId expectedId = MIN;
        when(rStream.add(any())).thenReturn(expectedId);

        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = "test-topic";
        record.payload = "test-payload";

        // When
        StreamMessageId result = service.send(record);

        // Then
        assertEquals(expectedId, result);
        verify(redissonClient).getStream("stream:topic:test-topic:dlq");
        verify(rStream).add(any());
    }

    @Test
    void send_withAllFields_addsCompleteEntry() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId expectedId = new StreamMessageId(12345, 0);
        when(rStream.add(any())).thenReturn(expectedId);

        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = "test-topic";
        record.payload = "test-payload";
        record.headers = Map.of("key", "value");
        record.timestamp = Instant.now();
        record.retryCount = 3;
        record.maxRetries = 5;
        record.originalMessageId = "msg-123";
        record.originalPartition = 2;

        // When
        StreamMessageId result = service.send(record);

        // Then
        assertNotNull(result);
        verify(rStream).add(any());
    }

    @Test
    void send_withNullFields_addsEntryWithDefaults() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        when(rStream.add(any())).thenReturn(new StreamMessageId(1, 0));

        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = "test-topic";
        record.payload = "test-payload";
        // Other fields left as default (null or default values)

        // When
        service.send(record);

        // Then
        verify(rStream).add(any());
    }

    // ==================== range() Tests ====================

    @Test
    void range_withValidLimit_returnsEntries() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        Map<StreamMessageId, Map<String, Object>> expected = Map.of(
                new StreamMessageId(1234567890L, 0), Map.of("key", "value")
        );
        when(rStream.range(10, StreamMessageId.MIN, StreamMessageId.MAX)).thenReturn(expected);

        // When
        Map<StreamMessageId, Map<String, Object>> result = service.range("test-topic", 10);

        // Then
        assertEquals(expected, result);
    }

    @Test
    void range_withZeroLimit_returnsEmptyMap() {
        // Given
        service = new RedisDeadLetterService(redissonClient);

        // When
        Map<StreamMessageId, Map<String, Object>> result = service.range("test-topic", 0);

        // Then
        assertTrue(result.isEmpty());
        verify(rStream, never()).range(anyInt(), any(), any());
    }

    @Test
    void range_withNegativeLimit_returnsEmptyMap() {
        // Given
        service = new RedisDeadLetterService(redissonClient);

        // When
        Map<StreamMessageId, Map<String, Object>> result = service.range("test-topic", -1);

        // Then
        assertTrue(result.isEmpty());
        verify(rStream, never()).range(anyInt(), any(), any());
    }

    @Test
    void range_whenExceptionThrown_returnsEmptyMap() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        when(rStream.range(anyInt(), any(), any())).thenThrow(new RuntimeException("Redis error"));

        // When
        Map<StreamMessageId, Map<String, Object>> result = service.range("test-topic", 10);

        // Then
        assertTrue(result.isEmpty());
    }

    // ==================== size() Tests ====================

    @Test
    void size_withExistingStream_returnsSize() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        when(rStream.size()).thenReturn(5L);

        // When
        long result = service.size("test-topic");

        // Then
        assertEquals(5, result);
    }

    @Test
    void size_withZeroSize_fallsBackToRange() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        when(rStream.size()).thenReturn(0L);
        Map<StreamMessageId, Map<String, Object>> rangeResult = Map.of(
                new StreamMessageId(1, 0), Map.of("key", "value")
        );
        when(rStream.range(1, StreamMessageId.MIN, StreamMessageId.MAX)).thenReturn(rangeResult);

        // When
        long result = service.size("test-topic");

        // Then
        assertEquals(1, result);
    }

    @Test
    void size_withEmptyRange_returnsZero() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        when(rStream.size()).thenReturn(0L);
        when(rStream.range(1, StreamMessageId.MIN, StreamMessageId.MAX)).thenReturn(Map.of());

        // When
        long result = service.size("test-topic");

        // Then
        assertEquals(0, result);
    }

    @Test
    void size_whenExceptionThrown_returnsZero() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        when(rStream.size()).thenThrow(new RuntimeException("Redis error"));

        // When
        long result = service.size("test-topic");

        // Then
        assertEquals(0, result);
    }

    // ==================== delete() Tests ====================

    @Test
    void delete_withExistingEntry_returnsTrue() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId id = MIN;
        when(rStream.remove(id)).thenReturn(1L);

        // When
        boolean result = service.delete("test-topic", id);

        // Then
        assertTrue(result);
    }

    @Test
    void delete_withNonExistingEntry_returnsFalse() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId id = MIN;
        when(rStream.remove(id)).thenReturn(0L);

        // When
        boolean result = service.delete("test-topic", id);

        // Then
        assertFalse(result);
    }

    @Test
    void delete_whenExceptionThrown_returnsFalse() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId id = MIN;
        when(rStream.remove(id)).thenThrow(new RuntimeException("Redis error"));

        // When
        boolean result = service.delete("test-topic", id);

        // Then
        assertFalse(result);
    }

    // ==================== clear() Tests ====================

    @Test
    void clear_withExistingStream_returnsSize() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        when(rStream.size()).thenReturn(5L);
        when(rKeys.delete("stream:topic:test-topic:dlq")).thenReturn(1L);

        // When
        long result = service.clear("test-topic");

        // Then
        assertEquals(5, result);
    }

    @Test
    void clear_withNonExistingStream_returnsZero() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        when(rStream.size()).thenReturn(0L);
        when(rKeys.delete("stream:topic:test-topic:dlq")).thenReturn(0L);

        // When
        long result = service.clear("test-topic");

        // Then
        assertEquals(0, result);
    }

    @Test
    void clear_whenExceptionThrown_returnsZero() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        when(rStream.size()).thenThrow(new RuntimeException("Redis error"));

        // When
        long result = service.clear("test-topic");

        // Then
        assertEquals(0, result);
    }

    // ==================== replay() Tests with ReplayHandler ====================

    @Test
    void replay_withReplayHandler_andValidMessage_returnsTrue() {
        // Given
        service = new RedisDeadLetterService(redissonClient, replayHandler);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 2);
        data.put("payload", "test-payload");
        data.put("maxRetries", 5);

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(replayHandler.publish(eq("test-topic"), eq(2), any(), any(), eq(5))).thenReturn(true);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertTrue(result);
        verify(replayHandler).publish(eq("test-topic"), eq(2), eq("test-payload"), any(), eq(5));
    }

    @Test
    void replay_withReplayHandler_andMissingPayloadHash_returnsTrueWithMissingFlag() {
        // Given
        service = new RedisDeadLetterService(redissonClient, replayHandler);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 1);
        data.put("payload", "");
        Map<String, String> headers = new HashMap<>();
        headers.put("x-payload-storage-type", "hash");
        headers.put("x-payload-hash-ref", "payload:ref:123");
        data.put("headers", headers);

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(rBucket.get()).thenReturn(null); // Missing payload
        when(replayHandler.publish(eq("test-topic"), eq(1), any(), any(), eq(3))).thenReturn(true);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertTrue(result);
        verify(replayHandler).publish(eq("test-topic"), eq(1), any(), any(), eq(3));
    }

    @Test
    void replay_withReplayHandler_andFoundPayloadHash_returnsTrueWithPayload() {
        // Given
        service = new RedisDeadLetterService(redissonClient, replayHandler);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 1);
        data.put("payload", "");
        Map<String, String> headers = new HashMap<>();
        headers.put("x-payload-storage-type", "hash");
        headers.put("x-payload-hash-ref", "payload:ref:123");
        data.put("headers", headers);

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(rBucket.get()).thenReturn("{\"key\":\"value\"}"); // Found payload
        when(replayHandler.publish(eq("test-topic"), eq(1), any(), any(), eq(3))).thenReturn(true);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertTrue(result);
    }

    @Test
    void replay_withReplayHandler_andEmptyDlq_returnsFalse() {
        // Given
        service = new RedisDeadLetterService(redissonClient, replayHandler);
        StreamMessageId id = MIN;
        when(rStream.range(1, id, id)).thenReturn(Map.of());

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertFalse(result);
        verify(replayHandler, never()).publish(any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void replay_withReplayHandler_whenPublishFails_returnsFalse() {
        // Given
        service = new RedisDeadLetterService(redissonClient, replayHandler);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 1);
        data.put("payload", "test");
        data.put("maxRetries", 3);

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(replayHandler.publish(any(), anyInt(), any(), any(), anyInt())).thenReturn(false);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertFalse(result);
    }

    // ==================== replay() Tests without ReplayHandler ====================

    @Test
    void replay_withoutReplayHandler_addsToOriginalStream() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 2);
        data.put("payload", "test-payload");

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(rStream.add(any())).thenReturn(MAX);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertTrue(result);
        verify(redissonClient).getStream("stream:topic:test-topic:p:2");
        verify(rStream).add(any());
    }

    @Test
    void replay_withoutReplayHandler_whenAddFails_returnsFalse() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 1);
        data.put("payload", "test");

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(rStream.add(any())).thenReturn(null);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertFalse(result);
    }

    @Test
    void replay_withoutReplayHandler_withMissingPayloadHash_addsMissingFlag() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 1);
        data.put("payload", "");
        Map<String, String> headers = new HashMap<>();
        headers.put("x-payload-storage-type", "hash");
        headers.put("x-payload-hash-ref", "payload:ref:123");
        data.put("headers", headers);

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(rBucket.get()).thenReturn(null);
        when(rStream.add(any())).thenReturn(MAX);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertTrue(result);
        verify(rStream).add(any());
    }

    @Test
    void replay_withoutReplayHandler_withFoundPayloadHash_updatesTtl() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 1);
        data.put("payload", "");
        Map<String, String> headers = new HashMap<>();
        headers.put("x-payload-storage-type", "hash");
        headers.put("x-payload-hash-ref", "payload:ref:123");
        data.put("headers", headers);

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(rBucket.get()).thenReturn("{\"key\":\"value\"}");
        when(rStream.add(any())).thenReturn(MAX);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertTrue(result);
        verify(rBucket).set(any(), any(java.time.Duration.class));
    }

    @Test
    void replay_whenExceptionThrown_returnsFalse() {
        // Given
        service = new RedisDeadLetterService(redissonClient);
        StreamMessageId id = MIN;
        when(rStream.range(1, id, id)).thenThrow(new RuntimeException("Redis error"));

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertFalse(result);
    }

    @Test
    void replay_defaultPartitionId_whenMissingIsZero() {
        // Given
        service = new RedisDeadLetterService(redissonClient, replayHandler);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test");
        // No partitionId field

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(replayHandler.publish(eq("test-topic"), eq(0), any(), any(), eq(3))).thenReturn(true);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertTrue(result);
        verify(replayHandler).publish(eq("test-topic"), eq(0), any(), any(), eq(3));
    }

    @Test
    void replay_invalidPartitionId_whenNonNumericDefaultsToZero() {
        // Given
        service = new RedisDeadLetterService(redissonClient, replayHandler);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", "invalid");
        data.put("payload", "test");

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(replayHandler.publish(eq("test-topic"), eq(0), any(), any(), eq(3))).thenReturn(true);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertTrue(result);
        verify(replayHandler).publish(eq("test-topic"), eq(0), any(), any(), eq(3));
    }

    @Test
    void replay_numberPartitionId_convertsCorrectly() {
        // Given
        service = new RedisDeadLetterService(redissonClient, replayHandler);
        StreamMessageId id = MIN;
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 5.7);
        data.put("payload", "test");

        when(rStream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(replayHandler.publish(eq("test-topic"), eq(5), any(), any(), eq(3))).thenReturn(true);

        // When
        boolean result = service.replay("test-topic", id);

        // Then
        assertTrue(result);
        verify(replayHandler).publish(eq("test-topic"), eq(5), any(), any(), eq(3));
    }
}
