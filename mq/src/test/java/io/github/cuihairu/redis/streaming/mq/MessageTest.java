package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Message
 */
class MessageTest {

    @Test
    void testMessageCreation() {
        String topic = "test-topic";
        String payload = "test-payload";

        Message message = new Message(topic, payload);

        assertNotNull(message);
        assertEquals(topic, message.getTopic());
        assertEquals(payload, message.getPayload());
        assertNotNull(message.getTimestamp());
        assertEquals(0, message.getRetryCount());
        assertEquals(3, message.getMaxRetries());
        assertFalse(message.hasExceededMaxRetries());
    }

    @Test
    void testMessageWithHeaders() {
        String topic = "test-topic";
        String payload = "test-payload";
        Map<String, String> headers = Map.of("header1", "value1", "header2", "value2");

        Message message = new Message(topic, (Object) payload, headers);

        assertEquals(headers, message.getHeaders());
    }

    @Test
    void testMessageWithKey() {
        String topic = "test-topic";
        String key = "test-key";
        String payload = "test-payload";

        Message message = new Message(topic, key, payload);

        assertEquals(key, message.getKey());
    }

    @Test
    void testRetryLogic() {
        Message message = new Message("test-topic", "test-payload");
        message.setMaxRetries(2);

        assertFalse(message.hasExceededMaxRetries());

        message.incrementRetryCount();
        assertEquals(1, message.getRetryCount());
        assertFalse(message.hasExceededMaxRetries());

        message.incrementRetryCount();
        assertEquals(2, message.getRetryCount());
        assertTrue(message.hasExceededMaxRetries());
    }

    @Test
    void testMessageHandleResultEnum() {
        assertNotNull(MessageHandleResult.SUCCESS);
        assertNotNull(MessageHandleResult.RETRY);
        assertNotNull(MessageHandleResult.FAIL);
        assertNotNull(MessageHandleResult.DEAD_LETTER);
    }

    @Test
    void testDefaultConstructor() {
        Message message = new Message();

        assertNull(message.getId());
        assertNull(message.getTopic());
        assertNull(message.getPayload());
        assertNull(message.getHeaders());
        assertNull(message.getTimestamp());
        assertNull(message.getKey());
        assertNull(message.getPublisher());
        assertEquals(0, message.getRetryCount());
        assertEquals(0, message.getMaxRetries());
    }

    @Test
    void testAllArgsConstructor() {
        Instant timestamp = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");

        Message message = new Message("id1", "topic1", "payload1", headers, timestamp, "key1", "publisher1", 2, 5);

        assertEquals("id1", message.getId());
        assertEquals("topic1", message.getTopic());
        assertEquals("payload1", message.getPayload());
        assertEquals(headers, message.getHeaders());
        assertEquals(timestamp, message.getTimestamp());
        assertEquals("key1", message.getKey());
        assertEquals("publisher1", message.getPublisher());
        assertEquals(2, message.getRetryCount());
        assertEquals(5, message.getMaxRetries());
    }

    @Test
    void testConstructorWithTopicPayloadHeadersAndPublisher() {
        Map<String, String> headers = new HashMap<>();
        headers.put("key", "value");

        Message message = new Message("topic1", (Object) "payload1", headers, "publisher1");

        assertEquals("topic1", message.getTopic());
        assertEquals("payload1", message.getPayload());
        assertEquals(headers, message.getHeaders());
        assertEquals("publisher1", message.getPublisher());
        assertNotNull(message.getTimestamp());
        assertEquals(0, message.getRetryCount());
        assertEquals(3, message.getMaxRetries());
    }

    @Test
    void testConstructorWithTopicPayloadAndHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");

        Message message = new Message("topic1", (Object) "payload1", headers);

        assertEquals("topic1", message.getTopic());
        assertEquals("payload1", message.getPayload());
        assertEquals(headers, message.getHeaders());
        assertNotNull(message.getTimestamp());
        assertEquals(0, message.getRetryCount());
        assertEquals(3, message.getMaxRetries());
    }

    @Test
    void testConstructorWithTopicKeyPayloadAndPublisher() {
        Message message = new Message("topic1", "key1", "payload1", "publisher1");

        assertEquals("topic1", message.getTopic());
        assertEquals("key1", message.getKey());
        assertEquals("payload1", message.getPayload());
        assertEquals("publisher1", message.getPublisher());
        assertNotNull(message.getTimestamp());
        assertEquals(0, message.getRetryCount());
        assertEquals(3, message.getMaxRetries());
    }

    @Test
    void testHasExceededMaxRetriesWithZeroMaxRetries() {
        Message message = new Message();
        message.setRetryCount(0);
        message.setMaxRetries(0);

        assertTrue(message.hasExceededMaxRetries());
    }

    @Test
    void testIncrementRetryCount() {
        Message message = new Message();
        message.setRetryCount(0);

        message.incrementRetryCount();
        assertEquals(1, message.getRetryCount());

        message.incrementRetryCount();
        assertEquals(2, message.getRetryCount());

        message.incrementRetryCount();
        assertEquals(3, message.getRetryCount());
    }

    @Test
    void testSettersAndGetters() {
        Message message = new Message();
        Instant timestamp = Instant.now();
        Map<String, String> headers = new HashMap<>();
        headers.put("key", "value");

        message.setId("test-id");
        message.setTopic("test-topic");
        message.setPayload("test-payload");
        message.setHeaders(headers);
        message.setTimestamp(timestamp);
        message.setKey("test-key");
        message.setPublisher("test-publisher");
        message.setRetryCount(5);
        message.setMaxRetries(10);

        assertEquals("test-id", message.getId());
        assertEquals("test-topic", message.getTopic());
        assertEquals("test-payload", message.getPayload());
        assertEquals(headers, message.getHeaders());
        assertEquals(timestamp, message.getTimestamp());
        assertEquals("test-key", message.getKey());
        assertEquals("test-publisher", message.getPublisher());
        assertEquals(5, message.getRetryCount());
        assertEquals(10, message.getMaxRetries());
    }

    @Test
    void testWithNullValues() {
        Message message = new Message(null, null);

        assertNull(message.getTopic());
        assertNull(message.getPayload());
        assertNotNull(message.getTimestamp());
        assertEquals(0, message.getRetryCount());
        assertEquals(3, message.getMaxRetries());
    }

    @Test
    void testWithEmptyPayload() {
        Message message = new Message("topic", "");

        assertEquals("topic", message.getTopic());
        assertEquals("", message.getPayload());
        assertNotNull(message.getTimestamp());
    }

    @Test
    void testWithComplexPayload() {
        Map<String, Object> complexPayload = new HashMap<>();
        complexPayload.put("field1", "value1");
        complexPayload.put("field2", 123);

        Message message = new Message("topic", complexPayload);

        assertEquals("topic", message.getTopic());
        assertEquals(complexPayload, message.getPayload());
    }

    @Test
    void testMultipleIncrementRetryCount() {
        Message message = new Message();
        message.setMaxRetries(5);

        for (int i = 0; i < 5; i++) {
            message.incrementRetryCount();
        }

        assertEquals(5, message.getRetryCount());
        assertTrue(message.hasExceededMaxRetries()); // retryCount (5) >= maxRetries (5)

        message.incrementRetryCount();
        assertEquals(6, message.getRetryCount());
        assertTrue(message.hasExceededMaxRetries());
    }

    @Test
    void testLombokDataAnnotation() {
        Instant timestamp = Instant.now();
        Map<String, String> headers = new HashMap<>();

        Message message1 = new Message("id1", "topic1", "payload1", headers, timestamp, "key1", "pub1", 1, 3);
        Message message2 = new Message("id1", "topic1", "payload1", headers, timestamp, "key1", "pub1", 1, 3);

        assertEquals(message1, message2);
        assertEquals(message1.hashCode(), message2.hashCode());
    }

    @Test
    void testDifferentRetryScenarios() {
        Message message = new Message();
        message.setMaxRetries(3);

        // Not exceeded
        message.setRetryCount(0);
        assertFalse(message.hasExceededMaxRetries());

        message.setRetryCount(1);
        assertFalse(message.hasExceededMaxRetries());

        message.setRetryCount(2);
        assertFalse(message.hasExceededMaxRetries());

        // At limit
        message.setRetryCount(3);
        assertTrue(message.hasExceededMaxRetries());

        // Over limit
        message.setRetryCount(4);
        assertTrue(message.hasExceededMaxRetries());
    }

    @Test
    void testHeadersModification() {
        Map<String, String> headers = new HashMap<>();
        headers.put("key1", "value1");

        Message message = new Message("topic", (Object) "payload", headers);

        assertEquals("value1", message.getHeaders().get("key1"));

        message.getHeaders().put("key2", "value2");
        assertEquals("value2", message.getHeaders().get("key2"));
    }

    @Test
    void testNullHeaders() {
        Message message = new Message("topic", (Object) "payload", null);

        assertNull(message.getHeaders());
        assertEquals("topic", message.getTopic());
        assertEquals("payload", message.getPayload());
    }

    @Test
    void testPublisherAutoGenerated() {
        Message message = new Message("topic", "payload");

        assertNotNull(message.getPublisher());
        assertFalse(message.getPublisher().isEmpty());
    }
}