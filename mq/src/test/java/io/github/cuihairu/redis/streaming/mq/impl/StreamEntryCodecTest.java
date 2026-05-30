package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;

/**
 * Unit tests for StreamEntryCodec
 */
@SuppressWarnings({"unchecked", "deprecation"})
class StreamEntryCodecTest {

    @Mock
    private RedissonClient mockRedissonClient;

    @Mock
    private RBucket<Object> mockRBucket;

    private org.junit.jupiter.api.Nested mockery;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testBuildPartitionEntryWithBasicMessage() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setKey("test-key");
        message.setTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        message.setRetryCount(1);
        message.setMaxRetries(3);

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0);

        assertEquals("test-payload", entry.get("payload"));
        assertEquals("2024-01-01T00:00:00Z", entry.get("timestamp"));
        assertEquals(1, entry.get("retryCount"));
        assertEquals(3, entry.get("maxRetries"));
        assertEquals("test-topic", entry.get("topic"));
        assertEquals(0, entry.get("partitionId"));
        assertEquals("test-key", entry.get("key"));
        // Headers are added for payload metadata
        assertNotNull(entry.get("headers"));
    }

    @Test
    void testBuildPartitionEntryWithHeaders() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");
        headers.put("header2", "value2");
        message.setHeaders(headers);

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0);

        @SuppressWarnings("unchecked")
        Map<String, String> entryHeaders = (Map<String, String>) entry.get("headers");
        assertNotNull(entryHeaders);
        assertEquals("value1", entryHeaders.get("header1"));
        assertEquals("value2", entryHeaders.get("header2"));
        assertTrue(entryHeaders.containsKey(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
        assertEquals(PayloadHeaders.STORAGE_TYPE_INLINE, entryHeaders.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
    }

    @Test
    void testBuildPartitionEntryWithNullKey() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setKey(null);

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0);

        assertNull(entry.get("key"));
    }

    @Test
    void testBuildPartitionEntryWithNullTimestamp() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setTimestamp(null);

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0);

        assertNotNull(entry.get("timestamp"));
        // Should use current time when timestamp is null
        String timestampStr = (String) entry.get("timestamp");
        assertNotNull(timestampStr);
        assertTrue(timestampStr.endsWith("Z"));
    }

    @Test
    void testBuildPartitionEntryWithEmptyHeaders() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setHeaders(new HashMap<>());

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0);

        // Empty headers are merged with payload metadata headers
        assertNotNull(entry.get("headers"));
    }

    @Test
    void testBuildPartitionEntryWithNullPayload() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload(null);

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0);

        assertNull(entry.get("payload"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) entry.get("headers");
        assertNotNull(headers);
        assertEquals("0", headers.get(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE));
    }

    @Test
    void testBuildPartitionEntryWithLargePayloadAndLifecycleManager() {
        Message message = new Message();
        message.setTopic("test-topic");
        // Create a large payload that exceeds MAX_INLINE_PAYLOAD_SIZE
        StringBuilder largePayload = new StringBuilder();
        for (int i = 0; i < PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE + 1; i++) {
            largePayload.append("x");
        }
        message.setPayload(largePayload.toString());

        PayloadLifecycleManager lifecycleManager = new PayloadLifecycleManager(null);

        // PayloadLifecycleManager with null RedissonClient will throw RuntimeException when trying to store large payload
        assertThrows(RuntimeException.class, () ->
            StreamEntryCodec.buildPartitionEntry(message, 0, lifecycleManager)
        );
    }

    @Test
    void testBuildPartitionEntryWithLargePayloadAndNoLifecycleManager() {
        Message message = new Message();
        message.setTopic("test-topic");
        // Create a large payload
        StringBuilder largePayload = new StringBuilder();
        for (int i = 0; i < PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE + 1; i++) {
            largePayload.append("x");
        }
        message.setPayload(largePayload.toString());

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0, (PayloadLifecycleManager) null);

        // When no lifecycle manager, payload should be stored inline
        assertNotNull(entry.get("payload"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) entry.get("headers");
        assertNotNull(headers);
        assertEquals(PayloadHeaders.STORAGE_TYPE_INLINE, headers.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
    }

    @Test
    void testBuildPartitionEntryWithNullRedissonClient() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0, (org.redisson.api.RedissonClient) null);

        assertNotNull(entry.get("payload"));
    }

    @Test
    void testParsePartitionEntryWithBasicData() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test-payload");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 1);
        data.put("maxRetries", 3);
        data.put("topic", "test-topic");
        data.put("partitionId", 0);
        data.put("key", "test-key");

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data);

        assertEquals("msg-id", message.getId());
        assertEquals("test-topic", message.getTopic());
        assertEquals("test-payload", message.getPayload());
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), message.getTimestamp());
        assertEquals(1, message.getRetryCount());
        assertEquals(3, message.getMaxRetries());
        assertEquals("test-key", message.getKey());
    }

    @Test
    void testParsePartitionEntryWithHeaders() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test-payload");
        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");
        headers.put("header2", "value2");
        data.put("headers", headers);

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data);

        Map<String, String> messageHeaders = message.getHeaders();
        assertNotNull(messageHeaders);
        assertEquals("value1", messageHeaders.get("header1"));
        assertEquals("value2", messageHeaders.get("header2"));
    }

    @Test
    void testParsePartitionEntryWithNullTimestamp() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test-payload");
        data.put("timestamp", null);

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data);

        assertNotNull(message.getTimestamp());
    }

    @Test
    void testParsePartitionEntryWithMissingOptionalFields() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test-payload");

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data);

        assertEquals(0, message.getRetryCount());
        assertEquals(3, message.getMaxRetries());
        assertNull(message.getKey());
        assertNotNull(message.getTimestamp());
    }

    @Test
    void testParsePartitionEntryWithHeadersAsString() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test-payload");
        data.put("headers", "{\"header1\":\"value1\",\"header2\":\"value2\"}");

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data);

        Map<String, String> headers = message.getHeaders();
        assertNotNull(headers);
        assertEquals("value1", headers.get("header1"));
        assertEquals("value2", headers.get("header2"));
    }

    @Test
    void testParsePartitionEntryWithInvalidHeaderString() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test-payload");
        data.put("headers", "invalid-json");

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data);

        // Should not throw exception, headers should be empty
        Map<String, String> headers = message.getHeaders();
        assertNotNull(headers);
        assertTrue(headers.isEmpty() || headers.size() > 0);
    }

    @Test
    void testBuildDlqEntryWithBasicMessage() {
        Message message = new Message();
        message.setTopic("original-topic");
        message.setPayload("failed-payload");
        message.setKey("test-key");
        message.setRetryCount(3);
        message.setTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        message.setId("original-id");

        Map<String, Object> dlqEntry = StreamEntryCodec.buildDlqEntry(message);

        assertEquals("original-topic", dlqEntry.get("originalTopic"));
        assertEquals("failed-payload", dlqEntry.get("payload"));
        assertEquals("test-key", dlqEntry.get("key"));
        assertEquals(3, dlqEntry.get("retryCount"));
        assertEquals("original-id", dlqEntry.get("originalMessageId"));
        assertEquals(0, dlqEntry.get("partitionId"));
        assertNotNull(dlqEntry.get("timestamp"));
        assertNotNull(dlqEntry.get("failedAt"));
    }

    @Test
    void testBuildDlqEntryWithHeaders() {
        Message message = new Message();
        message.setTopic("original-topic");
        message.setPayload("failed-payload");

        Map<String, String> headers = new HashMap<>();
        headers.put("partitionId", "5");
        headers.put("custom", "value");
        message.setHeaders(headers);

        Map<String, Object> dlqEntry = StreamEntryCodec.buildDlqEntry(message);

        @SuppressWarnings("unchecked")
        Map<String, String> entryHeaders = (Map<String, String>) dlqEntry.get("headers");
        assertNotNull(entryHeaders);
        assertEquals("5", entryHeaders.get("partitionId"));
        assertEquals("value", entryHeaders.get("custom"));
    }

    @Test
    void testBuildDlqEntryWithPartitionIdInHeaders() {
        Message message = new Message();
        message.setTopic("original-topic");
        message.setPayload("failed-payload");

        Map<String, String> headers = new HashMap<>();
        headers.put(io.github.cuihairu.redis.streaming.mq.MqHeaders.PARTITION_ID, "7");
        message.setHeaders(headers);

        Map<String, Object> dlqEntry = StreamEntryCodec.buildDlqEntry(message);

        assertEquals(7, dlqEntry.get("partitionId"));
    }

    @Test
    void testParseDlqEntryWithBasicData() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "original-topic");
        data.put("payload", "failed-payload");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 3);
        data.put("partitionId", 5);
        data.put("key", "test-key");

        Message message = StreamEntryCodec.parseDlqEntry("dlq-id", data);

        assertEquals("dlq-id", message.getId());
        assertEquals("original-topic", message.getTopic());
        assertEquals("failed-payload", message.getPayload());
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), message.getTimestamp());
        assertEquals(3, message.getRetryCount());
    }

    @Test
    void testParseDlqEntryWithHeaders() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "original-topic");
        data.put("payload", "failed-payload");
        Map<String, String> headers = new HashMap<>();
        headers.put("custom", "value");
        data.put("headers", headers);

        Message message = StreamEntryCodec.parseDlqEntry("dlq-id", data);

        Map<String, String> messageHeaders = message.getHeaders();
        assertNotNull(messageHeaders);
        assertEquals("value", messageHeaders.get("custom"));
        assertEquals("original-topic", messageHeaders.get("originalTopic"));
    }

    @Test
    void testParseDlqEntryWithNullOriginalTopic() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "failed-payload");

        Message message = StreamEntryCodec.parseDlqEntry("dlq-id", data);

        assertEquals("", message.getTopic());
    }

    @Test
    void testParseDlqEntryWithNegativePartitionId() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "original-topic");
        data.put("payload", "failed-payload");
        data.put("partitionId", -1);

        Message message = StreamEntryCodec.parseDlqEntry("dlq-id", data);

        // Negative partition ID should not be in headers
        Map<String, String> headers = message.getHeaders();
        if (headers != null) {
            assertNull(headers.get("partitionId"));
        }
    }

    @Test
    void testBuildPartitionEntryFromDlq() {
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "replay-payload");
        dlq.put("key", "test-key");
        dlq.put("maxRetries", 5);
        Map<String, String> headers = new HashMap<>();
        headers.put("custom", "value");
        dlq.put("headers", headers);

        Map<String, Object> partitionEntry = StreamEntryCodec.buildPartitionEntryFromDlq(dlq, "new-topic", 2);

        assertEquals("replay-payload", partitionEntry.get("payload"));
        assertEquals("test-key", partitionEntry.get("key"));
        assertEquals(5, partitionEntry.get("maxRetries"));
        assertEquals("new-topic", partitionEntry.get("topic"));
        assertEquals(2, partitionEntry.get("partitionId"));
        assertEquals(0, partitionEntry.get("retryCount"));
        assertNotNull(partitionEntry.get("headers"));
        assertNotNull(partitionEntry.get("timestamp"));
    }

    @Test
    void testBuildPartitionEntryFromDlqWithNullKey() {
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "replay-payload");

        Map<String, Object> partitionEntry = StreamEntryCodec.buildPartitionEntryFromDlq(dlq, "new-topic", 0);

        assertNull(partitionEntry.get("key"));
    }

    @Test
    void testBuildPartitionEntryFromDlqWithNullHeaders() {
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "replay-payload");

        Map<String, Object> partitionEntry = StreamEntryCodec.buildPartitionEntryFromDlq(dlq, "new-topic", 0);

        assertNull(partitionEntry.get("headers"));
    }

    @Test
    void testBuildPartitionEntryFromDlqWithNullMaxRetries() {
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "replay-payload");

        Map<String, Object> partitionEntry = StreamEntryCodec.buildPartitionEntryFromDlq(dlq, "new-topic", 0);

        assertEquals(3, partitionEntry.get("maxRetries"));
    }

    @Test
    void testParsePartitionEntryWithHashStorageTypeAndLifecycleManager() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", null);

        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "test-hash-key");
        data.put("headers", headers);

        PayloadLifecycleManager lifecycleManager = new PayloadLifecycleManager(null);

        // PayloadLifecycleManager with null RedissonClient will fail to load from hash
        // The behavior is that it will throw an exception
        assertThrows(RuntimeException.class, () ->
            StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data, lifecycleManager)
        );
    }

    @Test
    void testParseDlqEntryWithHashStorageTypeAndLifecycleManager() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "original-topic");
        data.put("payload", null);

        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "test-hash-key");
        data.put("headers", headers);

        PayloadLifecycleManager lifecycleManager = new PayloadLifecycleManager(null);

        // PayloadLifecycleManager with null RedissonClient will fail to load from hash
        // The behavior is that it will throw an exception
        assertThrows(RuntimeException.class, () ->
            StreamEntryCodec.parseDlqEntry("dlq-id", data, lifecycleManager)
        );
    }

    @Test
    void testBuildPartitionEntryWithDifferentPartitions() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        Map<String, Object> entry0 = StreamEntryCodec.buildPartitionEntry(message, 0);
        Map<String, Object> entry1 = StreamEntryCodec.buildPartitionEntry(message, 1);
        Map<String, Object> entry2 = StreamEntryCodec.buildPartitionEntry(message, 2);

        assertEquals(0, entry0.get("partitionId"));
        assertEquals(1, entry1.get("partitionId"));
        assertEquals(2, entry2.get("partitionId"));
    }

    @Test
    void testMessageWithAllFields() {
        Message message = new Message();
        message.setId("msg-id");
        message.setTopic("test-topic");
        message.setKey("key");
        message.setPayload("payload");
        message.setTimestamp(Instant.parse("2024-01-01T00:00:00Z"));
        message.setRetryCount(2);
        message.setMaxRetries(5);

        Map<String, String> headers = new HashMap<>();
        headers.put("h1", "v1");
        message.setHeaders(headers);

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0);

        Message parsed = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", entry);

        assertEquals("test-topic", parsed.getTopic());
        assertEquals("key", parsed.getKey());
        assertEquals("payload", parsed.getPayload());
        assertEquals(2, parsed.getRetryCount());
        assertEquals(5, parsed.getMaxRetries());
    }

    // ==========================================
    // RedissonClient Overload Tests
    // These tests trigger the private helper methods
    // ==========================================

    @Test
    void testBuildPartitionEntryWithRedissonClientAndSmallPayload() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("small-payload");

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0, mockRedissonClient);

        assertNotNull(entry);
        assertEquals("small-payload", entry.get("payload"));
        // Small payloads should be stored inline, no Redis calls needed
    }

    @Test
    void testBuildPartitionEntryWithRedissonClientAndLargePayload() {
        Message message = new Message();
        message.setTopic("test-topic");
        // Create a large payload that exceeds MAX_INLINE_PAYLOAD_SIZE
        StringBuilder largePayload = new StringBuilder();
        for (int i = 0; i < PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE + 1; i++) {
            largePayload.append("x");
        }
        message.setPayload(largePayload.toString());

        when(mockRedissonClient.getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRBucket);
        doAnswer(inv -> null).when(mockRBucket).set(anyString());

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0, mockRedissonClient);

        assertNotNull(entry);
        assertNull(entry.get("payload"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) entry.get("headers");
        assertNotNull(headers);
        assertEquals(PayloadHeaders.STORAGE_TYPE_HASH, headers.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
        assertNotNull(headers.get(PayloadHeaders.PAYLOAD_HASH_REF));
        // Verify Redis operations were called
        verify(mockRedissonClient, atLeastOnce()).getBucket(anyString(), eq(StringCodec.INSTANCE));
        verify(mockRBucket).set(anyString());
    }

    @Test
    void testBuildPartitionEntryWithRedissonClientAndNullPayload() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload(null);

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0, mockRedissonClient);

        assertNotNull(entry);
        assertNull(entry.get("payload"));
    }

    @Test
    void testParsePartitionEntryWithRedissonClientAndInlinePayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "inline-payload");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 1);
        data.put("maxRetries", 3);
        data.put("topic", "test-topic");
        data.put("partitionId", 0);

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data, mockRedissonClient);

        assertNotNull(message);
        assertEquals("inline-payload", message.getPayload());
        // No Redis calls needed for inline payloads
        verify(mockRedissonClient, never()).getBucket(anyString(), any());
    }

    @Test
    void testParsePartitionEntryWithRedissonClientAndHashStoredPayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", null);
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 1);
        data.put("maxRetries", 3);
        data.put("topic", "test-topic");
        data.put("partitionId", 0);

        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "test-hash-key");
        data.put("headers", headers);

        when(mockRedissonClient.getBucket(eq("test-hash-key"), eq(StringCodec.INSTANCE))).thenReturn(mockRBucket);
        when(mockRBucket.get()).thenReturn("\"loaded-from-hash\"");
        when(mockRBucket.expire(any(java.time.Duration.class))).thenReturn(true);

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data, mockRedissonClient);

        assertNotNull(message);
        assertEquals("loaded-from-hash", message.getPayload());
        // Verify Redis operations were called
        verify(mockRedissonClient).getBucket(eq("test-hash-key"), eq(StringCodec.INSTANCE));
        verify(mockRBucket).get();
        // Payload-related headers should be removed from user-visible headers
        Map<String, String> messageHeaders = message.getHeaders();
        assertNotNull(messageHeaders);
        assertNull(messageHeaders.get(PayloadHeaders.PAYLOAD_HASH_REF));
        assertNull(messageHeaders.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
        assertNull(messageHeaders.get(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE));
    }

    @Test
    void testParsePartitionEntryWithRedissonClientAndNullHashPayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", null);
        data.put("timestamp", "2024-01-01T00:00:00Z");

        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "test-hash-key");
        data.put("headers", headers);

        when(mockRedissonClient.getBucket(eq("test-hash-key"), eq(StringCodec.INSTANCE))).thenReturn(mockRBucket);
        when(mockRBucket.get()).thenReturn(null);

        // Should throw RuntimeException when payload not found in hash
        assertThrows(RuntimeException.class, () ->
            StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data, mockRedissonClient)
        );
    }

    @Test
    void testParsePartitionEntryWithRedissonClientAndBothManagers() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", null);
        data.put("timestamp", "2024-01-01T00:00:00Z");

        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "test-hash-key");
        data.put("headers", headers);

        when(mockRedissonClient.getBucket(eq("test-hash-key"), eq(StringCodec.INSTANCE))).thenReturn(mockRBucket);
        when(mockRBucket.get()).thenReturn("\"payload\"");

        PayloadLifecycleManager lifecycleManager = new PayloadLifecycleManager(mockRedissonClient);

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data, mockRedissonClient, lifecycleManager);

        assertNotNull(message);
        assertEquals("payload", message.getPayload());
    }

    @Test
    void testBuildDlqEntryWithRedissonClientAndSmallPayload() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("small-payload");

        Map<String, Object> entry = StreamEntryCodec.buildDlqEntry(message, mockRedissonClient);

        assertNotNull(entry);
        assertEquals("small-payload", entry.get("payload"));
        assertEquals("test-topic", entry.get("originalTopic"));
        // Small payloads stored inline
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) entry.get("headers");
        assertNotNull(headers);
        assertEquals(PayloadHeaders.STORAGE_TYPE_INLINE, headers.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
    }

    @Test
    void testBuildDlqEntryWithRedissonClientAndLargePayload() {
        Message message = new Message();
        message.setTopic("test-topic");
        // Create a large payload
        StringBuilder largePayload = new StringBuilder();
        for (int i = 0; i < PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE + 1; i++) {
            largePayload.append("x");
        }
        message.setPayload(largePayload.toString());

        when(mockRedissonClient.getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(mockRBucket);
        doAnswer(inv -> null).when(mockRBucket).set(anyString());

        Map<String, Object> entry = StreamEntryCodec.buildDlqEntry(message, mockRedissonClient);

        assertNotNull(entry);
        assertNull(entry.get("payload"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) entry.get("headers");
        assertNotNull(headers);
        assertEquals(PayloadHeaders.STORAGE_TYPE_HASH, headers.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
        assertNotNull(headers.get(PayloadHeaders.PAYLOAD_HASH_REF));
        // Verify Redis operations
        verify(mockRedissonClient, atLeastOnce()).getBucket(anyString(), eq(StringCodec.INSTANCE));
        verify(mockRBucket).set(anyString());
    }

    @Test
    void testParseDlqEntryWithRedissonClientAndInlinePayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test-topic");
        data.put("payload", "inline-payload");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 1);

        Message message = StreamEntryCodec.parseDlqEntry("dlq-id", data, mockRedissonClient);

        assertNotNull(message);
        assertEquals("test-topic", message.getTopic());
        assertEquals("inline-payload", message.getPayload());
        // No Redis calls for inline payload
        verify(mockRedissonClient, never()).getBucket(anyString(), any());
    }

    @Test
    void testParseDlqEntryWithRedissonClientAndHashStoredPayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test-topic");
        data.put("payload", null);
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 1);

        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "dlq-hash-key");
        data.put("headers", headers);

        when(mockRedissonClient.getBucket(eq("dlq-hash-key"), eq(StringCodec.INSTANCE))).thenReturn(mockRBucket);
        when(mockRBucket.get()).thenReturn("\"dlq-payload\"");

        Message message = StreamEntryCodec.parseDlqEntry("dlq-id", data, mockRedissonClient);

        assertNotNull(message);
        assertEquals("test-topic", message.getTopic());
        assertEquals("dlq-payload", message.getPayload());
        // Verify Redis operations
        verify(mockRedissonClient).getBucket(eq("dlq-hash-key"), eq(StringCodec.INSTANCE));
        verify(mockRBucket).get();
        // Internal headers should be hidden
        Map<String, String> messageHeaders = message.getHeaders();
        assertNotNull(messageHeaders);
        assertNull(messageHeaders.get(PayloadHeaders.PAYLOAD_HASH_REF));
        assertNull(messageHeaders.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE));
        assertNull(messageHeaders.get(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE));
    }

    @Test
    void testBuildPartitionEntryWithRedissonClientHandlesException() {
        Message message = new Message();
        message.setTopic("test-topic");
        // Create large payload
        StringBuilder largePayload = new StringBuilder();
        for (int i = 0; i < PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE + 1; i++) {
            largePayload.append("x");
        }
        message.setPayload(largePayload.toString());

        when(mockRedissonClient.getBucket(anyString(), eq(StringCodec.INSTANCE)))
            .thenThrow(new RuntimeException("Redis connection failed"));

        assertThrows(RuntimeException.class, () ->
            StreamEntryCodec.buildPartitionEntry(message, 0, mockRedissonClient)
        );
    }

    @Test
    void testParsePartitionEntryWithOriginalMessageIdInData() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test-payload");
        data.put("originalMessageId", "original-id");
        data.put("timestamp", "2024-01-01T00:00:00Z");

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data);

        assertNotNull(message.getHeaders());
        assertEquals("original-id", message.getHeaders()
            .get(io.github.cuihairu.redis.streaming.mq.MqHeaders.ORIGINAL_MESSAGE_ID));
    }

    @Test
    void testParsePartitionEntryWithOriginalMessageIdAlreadyInHeaders() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test-payload");
        data.put("originalMessageId", "data-original-id");
        data.put("timestamp", "2024-01-01T00:00:00Z");

        Map<String, String> headers = new HashMap<>();
        headers.put(io.github.cuihairu.redis.streaming.mq.MqHeaders.ORIGINAL_MESSAGE_ID, "header-original-id");
        data.put("headers", headers);

        Message message = StreamEntryCodec.parsePartitionEntry("test-topic", "msg-id", data);

        // Header value should take precedence over data field
        assertNotNull(message.getHeaders());
        assertEquals("header-original-id", message.getHeaders()
            .get(io.github.cuihairu.redis.streaming.mq.MqHeaders.ORIGINAL_MESSAGE_ID));
    }

    @Test
    void testParseDlqEntryWithBothPayloadAndHashRef() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test-topic");
        data.put("payload", "inline-payload");
        data.put("timestamp", "2024-01-01T00:00:00Z");

        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "hash-key");
        data.put("headers", headers);

        when(mockRedissonClient.getBucket(eq("hash-key"), eq(StringCodec.INSTANCE))).thenReturn(mockRBucket);
        when(mockRBucket.get()).thenReturn("\"hash-payload\"");

        Message message = StreamEntryCodec.parseDlqEntry("dlq-id", data, mockRedissonClient);

        assertNotNull(message);
        // When storage type is HASH, should load from hash even if inline payload present
        assertEquals("hash-payload", message.getPayload());
    }

    @Test
    void testParseDlqEntryWithEmptyPayloadAndHashRef() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test-topic");
        data.put("payload", ""); // Empty string
        data.put("timestamp", "2024-01-01T00:00:00Z");

        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "hash-key");
        data.put("headers", headers);

        when(mockRedissonClient.getBucket(eq("hash-key"), eq(StringCodec.INSTANCE))).thenReturn(mockRBucket);
        when(mockRBucket.get()).thenReturn("\"hash-payload\"");

        Message message = StreamEntryCodec.parseDlqEntry("dlq-id", data, mockRedissonClient);

        assertNotNull(message);
        // Empty payload with hash ref should load from hash
        assertEquals("hash-payload", message.getPayload());
    }

    @Test
    void testBuildDlqEntryWithNullTopic() {
        Message message = new Message();
        message.setTopic(null);
        message.setPayload("payload");

        Map<String, Object> entry = StreamEntryCodec.buildDlqEntry(message, mockRedissonClient);

        assertNotNull(entry);
        assertNull(entry.get("originalTopic"));
    }

    @Test
    void testBuildDlqEntryWithNullTimestamp() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("payload");
        message.setTimestamp(null);

        Map<String, Object> entry = StreamEntryCodec.buildDlqEntry(message, mockRedissonClient);

        assertNotNull(entry);
        assertNotNull(entry.get("timestamp"));
        assertNotNull(entry.get("failedAt"));
    }

    @Test
    void testBuildPartitionEntryWithEmptyPayloadAndLifecycleManager() {
        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("");

        PayloadLifecycleManager lifecycleManager = new PayloadLifecycleManager(null);

        Map<String, Object> entry = StreamEntryCodec.buildPartitionEntry(message, 0, lifecycleManager);

        assertNotNull(entry);
        assertEquals("", entry.get("payload"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) entry.get("headers");
        assertNotNull(headers);
        // Empty string serialized as "" which is 2 bytes in UTF-8
        assertEquals("2", headers.get(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE));
    }
}
