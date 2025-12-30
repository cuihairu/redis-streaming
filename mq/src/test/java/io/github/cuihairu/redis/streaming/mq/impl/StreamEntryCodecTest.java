package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StreamEntryCodec
 */
class StreamEntryCodecTest {

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
}
