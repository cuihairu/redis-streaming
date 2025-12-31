package io.github.cuihairu.redis.streaming.mq.retry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RetryEnvelopeTest {

    @Test
    public void testGettersSettersAndCtor() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of("h", "v"), 2, 3, "id");
        assertEquals("t", env.getTopic());
        assertEquals(1, env.getPartitionId());
        assertEquals("p", env.getPayload());
        assertEquals("k", env.getKey());
        assertEquals(Map.of("h", "v"), env.getHeaders());
        assertEquals(2, env.getRetryCount());
        assertEquals(3, env.getMaxRetries());
        assertEquals("id", env.getOriginalMessageId());

        env.setTopic("t2");
        env.setPartitionId(2);
        env.setPayload("p2");
        env.setKey("k2");
        env.setHeaders(Map.of());
        env.setRetryCount(4);
        env.setMaxRetries(5);
        env.setOriginalMessageId("id2");

        assertEquals("t2", env.getTopic());
        assertEquals(2, env.getPartitionId());
        assertEquals("p2", env.getPayload());
        assertEquals("k2", env.getKey());
        assertEquals(Map.of(), env.getHeaders());
        assertEquals(4, env.getRetryCount());
        assertEquals(5, env.getMaxRetries());
        assertEquals("id2", env.getOriginalMessageId());
    }

    @Test
    void defaultConstructorCreatesEmptyEnvelope() {
        RetryEnvelope env = new RetryEnvelope();

        assertNull(env.getTopic());
        assertNull(env.getPartitionId());
        assertNull(env.getPayload());
        assertNull(env.getKey());
        assertNull(env.getHeaders());
        assertEquals(0, env.getRetryCount());
        assertEquals(0, env.getMaxRetries());
        assertNull(env.getOriginalMessageId());
    }

    @Test
    void constructorWithNullValues() {
        RetryEnvelope env = new RetryEnvelope(null, null, null, null, null, 0, 0, null);

        assertNull(env.getTopic());
        assertNull(env.getPartitionId());
        assertNull(env.getPayload());
        assertNull(env.getKey());
        assertNull(env.getHeaders());
        assertEquals(0, env.getRetryCount());
        assertEquals(0, env.getMaxRetries());
        assertNull(env.getOriginalMessageId());
    }

    @Test
    void constructorWithEmptyHeaders() {
        RetryEnvelope env = new RetryEnvelope("topic", 0, "payload", "key", Map.of(), 1, 5, "msg-id");

        assertEquals(Map.of(), env.getHeaders());
    }

    @Test
    void constructorWithMultipleHeaders() {
        Map<String, String> headers = Map.of("h1", "v1", "h2", "v2", "h3", "v3");
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", headers, 0, 3, "id");

        assertEquals(3, env.getHeaders().size());
        assertEquals("v1", env.getHeaders().get("h1"));
        assertEquals("v2", env.getHeaders().get("h2"));
        assertEquals("v3", env.getHeaders().get("h3"));
    }

    @Test
    void setTopicWithNull() {
        RetryEnvelope env = new RetryEnvelope("topic", 1, "p", "k", Map.of(), 0, 3, "id");
        env.setTopic(null);

        assertNull(env.getTopic());
    }

    @Test
    void setTopicWithEmptyString() {
        RetryEnvelope env = new RetryEnvelope("topic", 1, "p", "k", Map.of(), 0, 3, "id");
        env.setTopic("");

        assertEquals("", env.getTopic());
    }

    @Test
    void setPartitionIdWithZero() {
        RetryEnvelope env = new RetryEnvelope("t", 5, "p", "k", Map.of(), 0, 3, "id");
        env.setPartitionId(0);

        assertEquals(0, env.getPartitionId());
    }

    @Test
    void setPartitionIdWithNegative() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 0, 3, "id");
        env.setPartitionId(-1);

        assertEquals(-1, env.getPartitionId());
    }

    @Test
    void setPayloadWithNull() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "payload", "k", Map.of(), 0, 3, "id");
        env.setPayload(null);

        assertNull(env.getPayload());
    }

    @Test
    void setPayloadWithDifferentTypes() {
        RetryEnvelope env = new RetryEnvelope();

        env.setPayload("string");
        assertEquals("string", env.getPayload());

        env.setPayload(123);
        assertEquals(123, env.getPayload());

        env.setPayload(45.67);
        assertEquals(45.67, env.getPayload());
    }

    @Test
    void setKeyWithNull() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "key", Map.of(), 0, 3, "id");
        env.setKey(null);

        assertNull(env.getKey());
    }

    @Test
    void setKeyWithEmptyString() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "key", Map.of(), 0, 3, "id");
        env.setKey("");

        assertEquals("", env.getKey());
    }

    @Test
    void setHeadersWithNull() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of("h", "v"), 0, 3, "id");
        env.setHeaders(null);

        assertNull(env.getHeaders());
    }

    @Test
    void setHeadersWithEmptyMap() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of("h", "v"), 0, 3, "id");
        env.setHeaders(Map.of());

        assertEquals(Map.of(), env.getHeaders());
    }

    @Test
    void setRetryCountWithZero() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 5, 10, "id");
        env.setRetryCount(0);

        assertEquals(0, env.getRetryCount());
    }

    @Test
    void setRetryCountWithNegative() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 5, 10, "id");
        env.setRetryCount(-1);

        assertEquals(-1, env.getRetryCount());
    }

    @Test
    void setRetryCountWithLargeValue() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 0, 100, "id");
        env.setRetryCount(999);

        assertEquals(999, env.getRetryCount());
    }

    @Test
    void setMaxRetriesWithZero() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 0, 10, "id");
        env.setMaxRetries(0);

        assertEquals(0, env.getMaxRetries());
    }

    @Test
    void setMaxRetriesWithNegative() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 0, 10, "id");
        env.setMaxRetries(-5);

        assertEquals(-5, env.getMaxRetries());
    }

    @Test
    void setMaxRetriesWithLargeValue() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 0, 3, "id");
        env.setMaxRetries(10000);

        assertEquals(10000, env.getMaxRetries());
    }

    @Test
    void setOriginalMessageIdWithNull() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 0, 3, "original-id");
        env.setOriginalMessageId(null);

        assertNull(env.getOriginalMessageId());
    }

    @Test
    void setOriginalMessageIdWithEmptyString() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 0, 3, "id");
        env.setOriginalMessageId("");

        assertEquals("", env.getOriginalMessageId());
    }

    @Test
    void retryCountCanEqualMaxRetries() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 5, 5, "id");

        assertEquals(5, env.getRetryCount());
        assertEquals(5, env.getMaxRetries());
    }

    @Test
    void retryCountCanBeGreaterThanMaxRetries() {
        RetryEnvelope env = new RetryEnvelope("t", 1, "p", "k", Map.of(), 10, 5, "id");

        assertEquals(10, env.getRetryCount());
        assertEquals(5, env.getMaxRetries());
    }

    @Test
    void payloadCanBeJsonObject() {
        Map<String, Object> obj = Map.of("field1", "value1", "field2", 123);
        RetryEnvelope env = new RetryEnvelope("t", 1, obj, "k", Map.of(), 0, 3, "id");

        assertEquals(obj, env.getPayload());
    }

    @Test
    void multipleSettersSameInstance() {
        RetryEnvelope env = new RetryEnvelope();

        env.setTopic("t1");
        env.setTopic("t2");
        assertEquals("t2", env.getTopic());

        env.setRetryCount(1);
        env.setRetryCount(2);
        env.setRetryCount(3);
        assertEquals(3, env.getRetryCount());
    }

    @Test
    void constructorWithZeroPartitionId() {
        RetryEnvelope env = new RetryEnvelope("topic", 0, "payload", "key", Map.of(), 0, 3, "id");

        assertEquals(0, env.getPartitionId());
    }

    @Test
    void constructorWithLargePartitionId() {
        RetryEnvelope env = new RetryEnvelope("topic", 9999, "p", "k", Map.of(), 0, 3, "id");

        assertEquals(9999, env.getPartitionId());
    }

    @Test
    void headersCanBeModifiedAfterSet() {
        RetryEnvelope env = new RetryEnvelope();
        Map<String, String> headers = Map.of("h1", "v1");

        env.setHeaders(headers);

        assertEquals("v1", env.getHeaders().get("h1"));
    }

    @Test
    void allFieldsCanBeSetIndependently() {
        RetryEnvelope env = new RetryEnvelope();

        env.setTopic("my-topic");
        env.setPartitionId(42);
        env.setPayload("my-payload");
        env.setKey("my-key");
        env.setHeaders(Map.of("header", "value"));
        env.setRetryCount(7);
        env.setMaxRetries(10);
        env.setOriginalMessageId("msg-123");

        assertEquals("my-topic", env.getTopic());
        assertEquals(42, env.getPartitionId());
        assertEquals("my-payload", env.getPayload());
        assertEquals("my-key", env.getKey());
        assertEquals("value", env.getHeaders().get("header"));
        assertEquals(7, env.getRetryCount());
        assertEquals(10, env.getMaxRetries());
        assertEquals("msg-123", env.getOriginalMessageId());
    }
}

