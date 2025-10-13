package io.github.cuihairu.redis.streaming.mq;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
}