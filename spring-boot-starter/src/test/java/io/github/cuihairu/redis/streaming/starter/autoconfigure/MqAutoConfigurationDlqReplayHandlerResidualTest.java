package io.github.cuihairu.redis.streaming.starter.autoconfigure;

import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.dlq.ReplayHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the DLQ replay handler lambda of {@code RedisStreamingMqAutoConfiguration}: header
 * propagation (including the forced partition id), null headers and the failure outcome.
 */
class MqAutoConfigurationDlqReplayHandlerResidualTest {

    private static ReplayHandler handler(MessageProducer producer) {
        return new RedisStreamingMqAutoConfiguration().dlqReplayHandler(
                mock(RedissonClient.class), MqOptions.builder().build(), producer);
    }

    @Test
    void publishPropagatesHeadersAndForcedPartition() {
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture("m-1"));

        assertTrue(handler(producer).publish("topic", 3, "payload", Map.of("trace", "t-1"), 5));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());
        Message sent = captor.getValue();
        assertEquals("topic", sent.getTopic());
        assertEquals("payload", sent.getPayload());
        assertEquals("t-1", sent.getHeaders().get("trace"));
        assertEquals("3", sent.getHeaders().get(MqHeaders.FORCE_PARTITION_ID));
    }

    @Test
    void publishToleratesNullHeaders() {
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send(any(Message.class))).thenReturn(CompletableFuture.completedFuture("m-1"));

        assertTrue(handler(producer).publish("topic", 0, "payload", null, 1));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(captor.capture());
        assertEquals("0", captor.getValue().getHeaders().get(MqHeaders.FORCE_PARTITION_ID));
    }

    @Test
    void publishReportsFailureWhenSendThrows() {
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send(any(Message.class))).thenThrow(new IllegalStateException("broker down"));

        assertFalse(handler(producer).publish("topic", 1, "payload", Map.of(), 1));
    }
}
