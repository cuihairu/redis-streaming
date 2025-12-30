package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BrokerBackedProducer
 */
class BrokerBackedProducerTest {

    @Mock
    private io.github.cuihairu.redis.streaming.mq.broker.Broker mockBroker;

    private BrokerBackedProducer createProducer() {
        MockitoAnnotations.openMocks(this);
        when(mockBroker.produce(any(Message.class))).thenReturn("test-message-id");
        return new BrokerBackedProducer(mockBroker);
    }

    @Test
    void testConstructorWithValidBroker() {
        BrokerBackedProducer producer = createProducer();

        assertNotNull(producer);
        assertFalse(producer.isClosed());
    }

    @Test
    void testConstructorWithNullBroker() {
        // The constructor accepts null broker
        assertDoesNotThrow(() -> new BrokerBackedProducer(null));
    }

    @Test
    void testSendWithMessage() throws Exception {
        BrokerBackedProducer producer = createProducer();

        Message message = new Message();
        message.setTopic("test-topic");
        message.setKey("test-key");
        message.setPayload("test-payload");

        CompletableFuture<String> future = producer.send(message);
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
        verify(mockBroker).produce(message);
    }

    @Test
    void testSendWithTopicKeyPayload() throws Exception {
        BrokerBackedProducer producer = createProducer();

        CompletableFuture<String> future = producer.send("test-topic", "test-key", "test-payload");
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
        verify(mockBroker).produce(any(Message.class));
    }

    @Test
    void testSendWithTopicPayload() throws Exception {
        BrokerBackedProducer producer = createProducer();

        CompletableFuture<String> future = producer.send("test-topic", "test-payload");
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
        verify(mockBroker).produce(any(Message.class));
    }

    @Test
    void testSendWithClosedProducer() throws Exception {
        BrokerBackedProducer producer = createProducer();
        producer.close();

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        CompletableFuture<String> future = producer.send(message);

        assertTrue(future.isCompletedExceptionally());
        assertThrows(ExecutionException.class, () -> future.get());
    }

    @Test
    void testClose() {
        BrokerBackedProducer producer = createProducer();

        assertFalse(producer.isClosed());
        producer.close();
        assertTrue(producer.isClosed());
    }

    @Test
    void testCloseIsIdempotent() {
        BrokerBackedProducer producer = createProducer();

        producer.close();
        producer.close(); // Should not cause issues
        assertTrue(producer.isClosed());
    }

    @Test
    void testSendWithMessageWithHeaders() throws Exception {
        BrokerBackedProducer producer = createProducer();

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        Map<String, String> headers = new HashMap<>();
        headers.put("header1", "value1");
        headers.put("header2", "value2");
        message.setHeaders(headers);

        CompletableFuture<String> future = producer.send(message);
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
    }

    @Test
    void testSendWithNullKey() throws Exception {
        BrokerBackedProducer producer = createProducer();

        Message message = new Message();
        message.setTopic("test-topic");
        message.setKey(null);
        message.setPayload("test-payload");

        CompletableFuture<String> future = producer.send(message);
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
    }

    @Test
    void testSendWithNullPayload() throws Exception {
        BrokerBackedProducer producer = createProducer();

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload(null);

        CompletableFuture<String> future = producer.send(message);
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
    }

    @Test
    void testSendWithNullTopic() throws Exception {
        BrokerBackedProducer producer = createProducer();

        Message message = new Message();
        message.setTopic(null);
        message.setPayload("test-payload");

        CompletableFuture<String> future = producer.send(message);
        // The broker should handle null topic
        assertNotNull(future);
    }

    @Test
    void testSendWithTopicAndNullKey() throws Exception {
        BrokerBackedProducer producer = createProducer();

        CompletableFuture<String> future = producer.send("test-topic", null, "test-payload");
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
    }

    @Test
    void testSendWithTopicAndNullPayload() throws Exception {
        BrokerBackedProducer producer = createProducer();

        CompletableFuture<String> future = producer.send("test-topic", (Object) null);
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
    }

    @Test
    void testMultipleSends() throws Exception {
        BrokerBackedProducer producer = createProducer();

        for (int i = 0; i < 5; i++) {
            Message message = new Message();
            message.setTopic("test-topic");
            message.setPayload("payload-" + i);

            CompletableFuture<String> future = producer.send(message);
            String messageId = future.get();

            assertEquals("test-message-id", messageId);
        }

        verify(mockBroker, times(5)).produce(any(Message.class));
    }

    @Test
    void testConcurrentSends() throws Exception {
        BrokerBackedProducer producer = createProducer();

        CompletableFuture<String>[] futures = new CompletableFuture[10];
        for (int i = 0; i < 10; i++) {
            Message message = new Message();
            message.setTopic("test-topic");
            message.setPayload("payload-" + i);
            futures[i] = producer.send(message);
        }

        for (CompletableFuture<String> future : futures) {
            String messageId = future.get();
            assertEquals("test-message-id", messageId);
        }
    }

    @Test
    void testSendWithAllMessageFields() throws Exception {
        BrokerBackedProducer producer = createProducer();

        Message message = new Message();
        message.setId("original-id");
        message.setTopic("test-topic");
        message.setKey("test-key");
        message.setPayload("test-payload");
        message.setRetryCount(2);
        message.setMaxRetries(5);

        Map<String, String> headers = new HashMap<>();
        headers.put("custom", "value");
        message.setHeaders(headers);

        CompletableFuture<String> future = producer.send(message);
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
    }

    @Test
    void testSendWithTopicKeyPayloadCreatesCorrectMessage() throws Exception {
        BrokerBackedProducer producer = createProducer();

        producer.send("test-topic", "test-key", "test-payload").get();

        verify(mockBroker).produce(argThat(msg ->
            msg != null &&
            "test-topic".equals(msg.getTopic()) &&
            "test-key".equals(msg.getKey()) &&
            "test-payload".equals(msg.getPayload())
        ));
    }

    @Test
    void testSendWithTopicPayloadCreatesCorrectMessage() throws Exception {
        BrokerBackedProducer producer = createProducer();

        producer.send("test-topic", "test-payload").get();

        verify(mockBroker).produce(argThat(msg ->
            msg != null &&
            "test-topic".equals(msg.getTopic()) &&
            "test-payload".equals(msg.getPayload())
        ));
    }

    @Test
    void testProducerAfterClose() {
        BrokerBackedProducer producer = createProducer();
        producer.close();

        assertTrue(producer.isClosed());

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload("test-payload");

        CompletableFuture<String> future = producer.send(message);

        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void testSendWithComplexPayload() throws Exception {
        BrokerBackedProducer producer = createProducer();

        Map<String, Object> complexPayload = new HashMap<>();
        complexPayload.put("field1", "value1");
        complexPayload.put("field2", 123);
        complexPayload.put("nested", Map.of("key", "value"));

        Message message = new Message();
        message.setTopic("test-topic");
        message.setPayload(complexPayload);

        CompletableFuture<String> future = producer.send(message);
        String messageId = future.get();

        assertEquals("test-message-id", messageId);
    }
}
