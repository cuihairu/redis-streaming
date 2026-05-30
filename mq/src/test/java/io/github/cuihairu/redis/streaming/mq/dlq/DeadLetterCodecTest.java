package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DeadLetterCodec encoding/decoding operations.
 */
class DeadLetterCodecTest {

    @Test
    void buildEntry_withAllFields_returnsCompleteMap() {
        // Given
        Instant now = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        headers.put("key2", "value2");
        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = "test-topic";
        record.payload = "test-payload";
        record.headers = headers;
        record.timestamp = now;
        record.retryCount = 2;
        record.maxRetries = 5;
        record.originalMessageId = "msg-123";
        record.originalPartition = 3;

        // When
        Map<String, Object> result = DeadLetterCodec.buildEntry(record);

        // Then
        assertEquals("test-topic", result.get("originalTopic"));
        assertEquals("test-payload", result.get("payload"));
        assertEquals(headers, result.get("headers"));
        assertTrue(result.containsKey("timestamp"));
        assertTrue(result.containsKey("failedAt"));
        assertEquals(2, result.get("retryCount"));
        assertEquals(3, result.get("partitionId"));
        assertEquals("msg-123", result.get("originalMessageId"));
        assertEquals(5, result.get("maxRetries"));
    }

    @Test
    void buildEntry_withNullTimestamp_usesNow() {
        // Given
        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = "test-topic";
        record.payload = "payload";
        record.headers = null;
        record.timestamp = null;
        record.retryCount = 0;
        record.maxRetries = 3;
        record.originalMessageId = null;
        record.originalPartition = 0;

        // When
        Map<String, Object> result = DeadLetterCodec.buildEntry(record);

        // Then
        assertNotNull(result.get("timestamp"));
        assertNotNull(result.get("failedAt"));
    }

    @Test
    void buildEntry_withNullHeaders_omitsHeadersField() {
        // Given
        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = "test-topic";
        record.payload = "payload";
        record.headers = null;
        record.timestamp = Instant.now();
        record.retryCount = 0;
        record.maxRetries = 3;
        record.originalMessageId = null;
        record.originalPartition = 0;

        // When
        Map<String, Object> result = DeadLetterCodec.buildEntry(record);

        // Then
        assertFalse(result.containsKey("headers"));
    }

    @Test
    void buildEntry_withEmptyHeaders_omitsHeadersField() {
        // Given
        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = "test-topic";
        record.payload = "payload";
        record.headers = new HashMap<>();
        record.timestamp = Instant.now();
        record.retryCount = 0;
        record.maxRetries = 3;
        record.originalMessageId = null;
        record.originalPartition = 0;

        // When
        Map<String, Object> result = DeadLetterCodec.buildEntry(record);

        // Then
        assertFalse(result.containsKey("headers"));
    }

    @Test
    void buildEntry_withNullOriginalMessageId_omitsMessageIdField() {
        // Given
        DeadLetterRecord record = new DeadLetterRecord();
        record.originalTopic = "test-topic";
        record.payload = "payload";
        record.headers = null;
        record.timestamp = Instant.now();
        record.retryCount = 0;
        record.maxRetries = 3;
        record.originalMessageId = null;
        record.originalPartition = 0;

        // When
        Map<String, Object> result = DeadLetterCodec.buildEntry(record);

        // Then
        assertFalse(result.containsKey("originalMessageId"));
    }

    @Test
    void buildPartitionEntryFromDlq_withCompleteData_returnsCorrectMap() {
        // Given
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "test-payload");
        dlq.put("key", "test-key");
        Map<String, String> headers = new HashMap<>();
        headers.put("h1", "v1");
        dlq.put("headers", headers);
        dlq.put("maxRetries", 5);

        // When
        Map<String, Object> result = DeadLetterCodec.buildPartitionEntryFromDlq(dlq, "new-topic", 2);

        // Then
        assertEquals("test-payload", result.get("payload"));
        assertEquals("test-key", result.get("key"));
        assertEquals("new-topic", result.get("topic"));
        assertEquals(2, result.get("partitionId"));
        assertEquals(5, result.get("maxRetries"));
        assertEquals(0, result.get("retryCount"));
        assertEquals(headers, result.get("headers"));
        assertTrue(result.containsKey("timestamp"));
    }

    @Test
    void buildPartitionEntryFromDlq_withNullMaxRetries_usesDefault() {
        // Given
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "test");

        // When
        Map<String, Object> result = DeadLetterCodec.buildPartitionEntryFromDlq(dlq, "topic", 0);

        // Then
        assertEquals(3, result.get("maxRetries"));
    }

    @Test
    void buildPartitionEntryFromDlq_withInvalidMaxRetries_usesDefault() {
        // Given
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "test");
        dlq.put("maxRetries", "invalid");

        // When
        Map<String, Object> result = DeadLetterCodec.buildPartitionEntryFromDlq(dlq, "topic", 0);

        // Then
        assertEquals(3, result.get("maxRetries"));
    }

    @Test
    void buildPartitionEntryFromDlq_withNumberMaxRetries_convertsCorrectly() {
        // Given
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "test");
        dlq.put("maxRetries", 7.5);

        // When
        Map<String, Object> result = DeadLetterCodec.buildPartitionEntryFromDlq(dlq, "topic", 0);

        // Then
        assertEquals(7, result.get("maxRetries"));
    }

    @Test
    void buildPartitionEntryFromDlq_withNullKey_omitsKeyField() {
        // Given
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "test");

        // When
        Map<String, Object> result = DeadLetterCodec.buildPartitionEntryFromDlq(dlq, "topic", 0);

        // Then
        assertFalse(result.containsKey("key"));
    }

    @Test
    void buildPartitionEntryFromDlq_withNullHeaders_omitsHeadersField() {
        // Given
        Map<String, Object> dlq = new HashMap<>();
        dlq.put("payload", "test");

        // When
        Map<String, Object> result = DeadLetterCodec.buildPartitionEntryFromDlq(dlq, "topic", 0);

        // Then
        assertFalse(result.containsKey("headers"));
    }

    @Test
    void parseEntry_withValidData_returnsCompleteEntry() {
        // Given
        String id = "1234567890-0";
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test-topic");
        data.put("partitionId", 3);
        data.put("payload", "test-payload");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 2);
        data.put("maxRetries", 5);
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        data.put("headers", headers);

        // When
        DeadLetterEntry result = DeadLetterCodec.parseEntry(id, data);

        // Then
        assertEquals(id, result.getId());
        assertEquals("test-topic", result.getOriginalTopic());
        assertEquals(3, result.getPartitionId());
        assertEquals("test-payload", result.getPayload());
        assertEquals(2, result.getRetryCount());
        assertEquals(5, result.getMaxRetries());
        assertTrue(result.getHeaders().containsKey("key1"));
        assertTrue(result.getHeaders().containsKey("originalTopic"));
        assertEquals("3", result.getHeaders().get("partitionId"));
    }

    @Test
    void parseEntry_withMissingOptionalFields_usesDefaults() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test-topic");
        data.put("payload", "test");

        // When
        DeadLetterEntry result = DeadLetterCodec.parseEntry("id", data);

        // Then
        assertEquals(0, result.getPartitionId());
        assertEquals(0, result.getRetryCount());
        assertEquals(3, result.getMaxRetries());
        assertNotNull(result.getTimestamp());
    }

    @Test
    void parseEntry_withNullTopic_usesEmptyString() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "test");

        // When
        DeadLetterEntry result = DeadLetterCodec.parseEntry("id", data);

        // Then
        assertEquals("", result.getOriginalTopic());
    }

    @Test
    void parseEntry_withInvalidTimestamp_usesNow() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test");
        data.put("payload", "test");
        data.put("timestamp", "invalid-timestamp");

        // When
        DeadLetterEntry result = DeadLetterCodec.parseEntry("id", data);

        // Then
        assertNotNull(result.getTimestamp());
        assertTrue(result.getTimestamp().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void parseEntry_withMapHeaders_parsesCorrectly() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test");
        data.put("payload", "test");
        Map<String, String> headers = new HashMap<>();
        headers.put("k1", "v1");
        headers.put("k2", "v2");
        data.put("headers", headers);

        // When
        DeadLetterEntry result = DeadLetterCodec.parseEntry("id", data);

        // Then
        assertEquals("v1", result.getHeaders().get("k1"));
        assertEquals("v2", result.getHeaders().get("k2"));
    }

    @Test
    void parseEntry_withStringHeaders_parsesJsonCorrectly() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test");
        data.put("payload", "test");
        data.put("headers", "{\"k1\":\"v1\",\"k2\":\"v2\"}");

        // When
        DeadLetterEntry result = DeadLetterCodec.parseEntry("id", data);

        // Then
        assertEquals("v1", result.getHeaders().get("k1"));
        assertEquals("v2", result.getHeaders().get("k2"));
    }

    @Test
    void parseEntry_withInvalidJsonHeaders_continuesWithoutError() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test");
        data.put("payload", "test");
        data.put("headers", "invalid-json");

        // When
        DeadLetterEntry result = DeadLetterCodec.parseEntry("id", data);

        // Then
        assertNotNull(result);
        assertTrue(result.getHeaders().containsKey("originalTopic"));
    }

    @Test
    void parseEntry_alwaysAddsMetadataHeaders() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test-topic");
        data.put("partitionId", 5);
        data.put("payload", "test");

        // When
        DeadLetterEntry result = DeadLetterCodec.parseEntry("id", data);

        // Then
        assertTrue(result.getHeaders().containsKey("originalTopic"));
        assertEquals("test-topic", result.getHeaders().get("originalTopic"));
        assertTrue(result.getHeaders().containsKey("partitionId"));
        assertEquals("5", result.getHeaders().get("partitionId"));
    }

    @Test
    void parseEntry_withNullHeaderValue_ignoresEntry() {
        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test");
        data.put("payload", "test");
        Map<String, Object> headers = new HashMap<>();
        headers.put("k1", null);
        headers.put("k2", "v2");
        data.put("headers", headers);

        // When
        DeadLetterEntry result = DeadLetterCodec.parseEntry("id", data);

        // Then
        assertFalse(result.getHeaders().containsKey("k1"));
        assertEquals("v2", result.getHeaders().get("k2"));
    }
}
