package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DLQ data models (DeadLetterRecord and DeadLetterEntry).
 */
class DeadLetterModelsTest {

    // ==================== DeadLetterRecord Tests ====================

    @Test
    void deadLetterRecord_withAllFields_setCorrectly() {
        // Given
        DeadLetterRecord record = new DeadLetterRecord();

        // When
        record.originalTopic = "test-topic";
        record.originalPartition = 3;
        record.originalMessageId = "msg-123";
        record.payload = "test-payload";
        record.headers = Map.of("key1", "value1");
        record.timestamp = Instant.now();
        record.retryCount = 2;
        record.maxRetries = 5;

        // Then
        assertEquals("test-topic", record.originalTopic);
        assertEquals(3, record.originalPartition);
        assertEquals("msg-123", record.originalMessageId);
        assertEquals("test-payload", record.payload);
        assertEquals("value1", record.headers.get("key1"));
        assertNotNull(record.timestamp);
        assertEquals(2, record.retryCount);
        assertEquals(5, record.maxRetries);
    }

    @Test
    void deadLetterRecord_defaultValues_areSetCorrectly() {
        // Given
        DeadLetterRecord record = new DeadLetterRecord();

        // Then
        assertEquals(0, record.originalPartition);
        assertNull(record.originalMessageId);
        assertNull(record.payload);
        assertNull(record.headers);
        assertNotNull(record.timestamp);
        assertEquals(0, record.retryCount);
        assertEquals(3, record.maxRetries);
    }

    @Test
    void deadLetterRecord_timestampDefault_isCurrentTime() {
        // Given
        Instant before = Instant.now();
        DeadLetterRecord record = new DeadLetterRecord();
        Instant after = Instant.now();

        // Then
        assertTrue(record.timestamp.isBefore(after.plusSeconds(1)));
        assertTrue(record.timestamp.isAfter(before.minusSeconds(1)));
    }

    @Test
    void deadLetterRecord_canUpdateIndividualFields() {
        // Given
        DeadLetterRecord record = new DeadLetterRecord();

        // When
        record.originalTopic = "updated-topic";
        record.retryCount = 10;

        // Then
        assertEquals("updated-topic", record.originalTopic);
        assertEquals(10, record.retryCount);
        // Other fields retain defaults
        assertEquals(0, record.originalPartition);
    }

    @Test
    void deadLetterRecord_withNullHeaders_worksCorrectly() {
        // Given
        DeadLetterRecord record = new DeadLetterRecord();
        record.headers = null;

        // Then
        assertNull(record.headers);
    }

    @Test
    void deadLetterRecord_withEmptyHeaders_worksCorrectly() {
        // Given
        DeadLetterRecord record = new DeadLetterRecord();
        record.headers = new HashMap<>();

        // Then
        assertTrue(record.headers.isEmpty());
    }

    // ==================== DeadLetterEntry Tests ====================

    @Test
    void deadLetterEntry_withAllFields_constructsCorrectly() {
        // Given
        Instant now = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        headers.put("key2", "value2");

        // When
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                5,
                "payload-data",
                headers,
                now,
                3,
                10
        );

        // Then
        assertEquals("id-123", entry.getId());
        assertEquals("test-topic", entry.getOriginalTopic());
        assertEquals(5, entry.getPartitionId());
        assertEquals("payload-data", entry.getPayload());
        assertEquals(2, entry.getHeaders().size());
        assertEquals("value1", entry.getHeaders().get("key1"));
        assertEquals("value2", entry.getHeaders().get("key2"));
        assertEquals(now, entry.getTimestamp());
        assertEquals(3, entry.getRetryCount());
        assertEquals(10, entry.getMaxRetries());
    }

    @Test
    void deadLetterEntry_withNullHeaders_constructsCorrectly() {
        // When
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                0,
                "payload",
                null,
                Instant.now(),
                0,
                3
        );

        // Then
        assertNull(entry.getHeaders());
    }

    @Test
    void deadLetterEntry_withEmptyHeaders_constructsCorrectly() {
        // When
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                0,
                "payload",
                new HashMap<>(),
                Instant.now(),
                0,
                3
        );

        // Then
        assertNotNull(entry.getHeaders());
        assertTrue(entry.getHeaders().isEmpty());
    }

    @Test
    void deadLetterEntry_withZeroPartitionId_constructsCorrectly() {
        // When
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                0,
                "payload",
                null,
                Instant.now(),
                0,
                3
        );

        // Then
        assertEquals(0, entry.getPartitionId());
    }

    @Test
    void deadLetterEntry_withNegativePartitionId_constructsCorrectly() {
        // When
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                -1,
                "payload",
                null,
                Instant.now(),
                0,
                3
        );

        // Then
        assertEquals(-1, entry.getPartitionId());
    }

    @Test
    void deadLetterEntry_withZeroRetryCount_constructsCorrectly() {
        // When
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                0,
                "payload",
                null,
                Instant.now(),
                0,
                3
        );

        // Then
        assertEquals(0, entry.getRetryCount());
    }

    @Test
    void deadLetterEntry_withLargeRetryCount_constructsCorrectly() {
        // When
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                0,
                "payload",
                null,
                Instant.now(),
                9999,
                10000
        );

        // Then
        assertEquals(9999, entry.getRetryCount());
        assertEquals(10000, entry.getMaxRetries());
    }

    @Test
    void deadLetterEntry_withNullPayload_constructsCorrectly() {
        // When
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                0,
                null,
                null,
                Instant.now(),
                0,
                3
        );

        // Then
        assertNull(entry.getPayload());
    }

    @Test
    void deadLetterEntry_withComplexPayload_constructsCorrectly() {
        // Given
        Map<String, Object> complexPayload = Map.of("key", "value", "number", 123);

        // When
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                0,
                complexPayload,
                null,
                Instant.now(),
                0,
                3
        );

        // Then
        assertEquals(complexPayload, entry.getPayload());
    }

    @Test
    void deadLetterEntry_headersReference_isStoredAsProvided() {
        // Given
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");
        DeadLetterEntry entry = new DeadLetterEntry(
                "id-123",
                "test-topic",
                0,
                "payload",
                headers,
                Instant.now(),
                0,
                3
        );

        // When
        headers.put("key2", "value2");

        // Then
        // Note: DeadLetterEntry does NOT make a defensive copy,
        // modifications to the original map are reflected
        assertEquals(2, entry.getHeaders().size());
        assertEquals("value1", entry.getHeaders().get("key1"));
        assertEquals("value2", entry.getHeaders().get("key2"));
    }

    @Test
    void deadLetterEntry_getters_returnCorrectValues() {
        // Given
        Instant now = Instant.now();
        Map<String, String> headers = Map.of("key", "value");
        DeadLetterEntry entry = new DeadLetterEntry(
                "test-id",
                "original-topic",
                7,
                "test-payload",
                headers,
                now,
                5,
                15
        );

        // Then - verify all getters return correct values
        assertEquals("test-id", entry.getId());
        assertEquals("original-topic", entry.getOriginalTopic());
        assertEquals(7, entry.getPartitionId());
        assertEquals("test-payload", entry.getPayload());
        assertEquals(headers, entry.getHeaders());
        assertEquals(now, entry.getTimestamp());
        assertEquals(5, entry.getRetryCount());
        assertEquals(15, entry.getMaxRetries());
    }
}
