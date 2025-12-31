package io.github.cuihairu.redis.streaming.reliability.dlq;

import org.junit.jupiter.api.Test;
import org.redisson.api.StreamMessageId;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeadLetterCodecTest {

    @Test
    void parseEntry_parsesStringHeadersJson() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-A");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 2);
        data.put("maxRetries", 5);
        data.put("headers", "{\"k1\":\"v1\",\"k2\":\"v2\"}");

        DeadLetterEntry e = DeadLetterCodec.parseEntry(new StreamMessageId(1,2).toString(), data);
        assertEquals("topic-A", e.getOriginalTopic());
        assertEquals(1, e.getPartitionId());
        assertEquals("p", e.getPayload());
        assertEquals(2, e.getRetryCount());
        assertEquals(5, e.getMaxRetries());
        assertEquals("v1", e.getHeaders().get("k1"));
        assertEquals("v2", e.getHeaders().get("k2"));
        // injected convenience headers
        assertEquals("topic-A", e.getHeaders().get("originalTopic"));
        assertEquals("1", e.getHeaders().get("partitionId"));
    }

    @Test
    void parseEntry_withMapHeaders() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-B");
        data.put("partitionId", 2);
        data.put("payload", "data");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 1);
        data.put("maxRetries", 3);

        Map<String, Object> headers = new HashMap<>();
        headers.put("h1", "v1");
        headers.put("h2", "v2");
        data.put("headers", headers);

        DeadLetterEntry e = DeadLetterCodec.parseEntry(new StreamMessageId(1,0).toString(), data);
        assertEquals("topic-B", e.getOriginalTopic());
        assertEquals(2, e.getPartitionId());
        assertEquals("v1", e.getHeaders().get("h1"));
        assertEquals("v2", e.getHeaders().get("h2"));
    }

    @Test
    void parseEntry_withNullTimestamp() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-C");
        data.put("partitionId", 0);
        data.put("payload", "test");
        data.put("timestamp", null);
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-1", data);
        assertNotNull(e.getTimestamp());
        // Should use current time when timestamp is null
        assertTrue(e.getTimestamp().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void parseEntry_withInvalidTimestamp() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-D");
        data.put("partitionId", 1);
        data.put("payload", "data");
        data.put("timestamp", "invalid-timestamp");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-2", data);
        assertNotNull(e.getTimestamp());
        // Should use current time when timestamp is invalid
        assertTrue(e.getTimestamp().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void parseEntry_withNullHeaders() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-E");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);
        data.put("headers", null);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-3", data);
        assertNotNull(e.getHeaders());
        // Should still have injected headers
        assertEquals("topic-E", e.getHeaders().get("originalTopic"));
        assertEquals("1", e.getHeaders().get("partitionId"));
    }

    @Test
    void parseEntry_withEmptyHeadersJson() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-F");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);
        data.put("headers", "{}");

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-4", data);
        assertNotNull(e.getHeaders());
        assertEquals(2, e.getHeaders().size()); // originalTopic + partitionId
    }

    @Test
    void parseEntry_withInvalidHeadersJson() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-G");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);
        data.put("headers", "invalid-json");

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-5", data);
        assertNotNull(e.getHeaders());
        // Should still have injected headers even if JSON parsing fails
        assertEquals("topic-G", e.getHeaders().get("originalTopic"));
        assertEquals("1", e.getHeaders().get("partitionId"));
    }

    @Test
    void parseEntry_withNullOriginalTopic() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", null);
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-6", data);
        assertEquals("", e.getOriginalTopic()); // null becomes empty string
    }

    @Test
    void parseEntry_withNullPartitionId() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-H");
        data.put("partitionId", null);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-7", data);
        assertEquals(0, e.getPartitionId()); // null becomes 0
    }

    @Test
    void parseEntry_withNullRetryCount() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-I");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", null);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-8", data);
        assertEquals(0, e.getRetryCount()); // null becomes 0
    }

    @Test
    void parseEntry_withNullMaxRetries() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-J");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", null);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-9", data);
        assertEquals(3, e.getMaxRetries()); // default is 3
    }

    @Test
    void parseEntry_withNumericPartitionId() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-K");
        data.put("partitionId", 5.5); // double value
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-10", data);
        assertEquals(5, e.getPartitionId()); // should convert to int
    }

    @Test
    void parseEntry_withStringPartitionId() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-L");
        data.put("partitionId", "10"); // string number
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-11", data);
        assertEquals(10, e.getPartitionId()); // should parse string
    }

    @Test
    void parseEntry_withObjectPayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-M");
        data.put("partitionId", 1);
        data.put("payload", 12345); // non-string payload
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-12", data);
        assertEquals(12345, e.getPayload());
    }

    @Test
    void parseEntry_withNullPayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-N");
        data.put("partitionId", 1);
        data.put("payload", null);
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-13", data);
        assertNull(e.getPayload());
    }

    @Test
    void parseEntry_preservesEntryId() {
        String expectedId = new StreamMessageId(123, 456).toString();
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-O");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry(expectedId, data);
        assertEquals(expectedId, e.getId());
    }

    @Test
    void parseEntry_withEmptyMap() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("k1", "v1");

        Map<String, Object> data = new HashMap<>();
        data.put("headers", headers);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-14", data);
        assertEquals("v1", e.getHeaders().get("k1"));
        assertEquals("", e.getOriginalTopic());
        assertEquals(0, e.getPartitionId());
    }

    @Test
    void parseEntry_handlesHeadersWithNullValues() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("k1", "v1");
        headers.put("k2", null); // null value should be skipped (not added to headers)
        headers.put(null, "v3"); // null key should be ignored

        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-P");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);
        data.put("headers", headers);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-15", data);
        assertEquals("v1", e.getHeaders().get("k1"));
        // null value in map is skipped, not added
        assertFalse(e.getHeaders().containsKey("k2"));
        assertFalse(e.getHeaders().containsKey("null"));
        // injected headers should still be present
        assertEquals("topic-P", e.getHeaders().get("originalTopic"));
        assertEquals("1", e.getHeaders().get("partitionId"));
    }

    @Test
    void parseEntry_withAllFieldsPresent() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "test-topic");
        data.put("partitionId", 3);
        data.put("payload", "test-payload");
        data.put("timestamp", "2024-06-15T12:30:45Z");
        data.put("retryCount", 5);
        data.put("maxRetries", 10);
        data.put("headers", "{\"type\":\"test\"}");

        DeadLetterEntry e = DeadLetterCodec.parseEntry("entry-id", data);
        assertEquals("entry-id", e.getId());
        assertEquals("test-topic", e.getOriginalTopic());
        assertEquals(3, e.getPartitionId());
        assertEquals("test-payload", e.getPayload());
        assertEquals(Instant.parse("2024-06-15T12:30:45Z"), e.getTimestamp());
        assertEquals(5, e.getRetryCount());
        assertEquals(10, e.getMaxRetries());
        assertEquals("test", e.getHeaders().get("type"));
    }

    @Test
    void parseEntry_withZeroRetryCount() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-Q");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-16", data);
        assertEquals(0, e.getRetryCount());
    }

    @Test
    void parseEntry_withMaxRetriesReached() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-R");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 5);
        data.put("maxRetries", 5);

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-17", data);
        assertEquals(5, e.getRetryCount());
        assertEquals(5, e.getMaxRetries());
    }

    @Test
    void parseEntry_withComplexHeadersJson() {
        Map<String, Object> data = new HashMap<>();
        data.put("originalTopic", "topic-S");
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("timestamp", "2024-01-01T00:00:00Z");
        data.put("retryCount", 0);
        data.put("maxRetries", 3);
        data.put("headers", "{\"contentType\":\"application/json\",\"encoding\":\"utf-8\",\"ttl\":3600}");

        DeadLetterEntry e = DeadLetterCodec.parseEntry("id-18", data);
        assertEquals("application/json", e.getHeaders().get("contentType"));
        assertEquals("utf-8", e.getHeaders().get("encoding"));
        assertEquals("3600", e.getHeaders().get("ttl"));
    }
}
