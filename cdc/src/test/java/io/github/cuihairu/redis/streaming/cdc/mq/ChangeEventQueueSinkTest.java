package io.github.cuihairu.redis.streaming.cdc.mq;

import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChangeEventQueueSinkTest {

    @Test
    void forwardsEventAsSelfDescribingPayload() throws Exception {
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send(anyString(), any(), any())).thenReturn(CompletableFuture.completedFuture("id"));

        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.UPDATE);
        event.setDatabase("shop");
        event.setTable("orders");
        event.setKey("42");
        event.setBeforeData(Map.of("status", "NEW"));
        event.setAfterData(Map.of("status", "PAID"));
        event.setTimestamp(Instant.ofEpochMilli(1700000000000L));

        ChangeEventQueueSink sink = new ChangeEventQueueSink(producer, "cdc-orders");
        sink.invoke(event);

        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(producer).send(eq("cdc-orders"), eq("42"), payloadCap.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCap.getValue();
        assertEquals("UPDATE", payload.get("eventType"));
        assertEquals("shop", payload.get("database"));
        assertEquals("orders", payload.get("table"));
        assertEquals("42", payload.get("key"));
        assertEquals(Map.of("status", "NEW"), payload.get("before"));
        assertEquals(Map.of("status", "PAID"), payload.get("after"));
        assertEquals("2023-11-14T22:13:20Z", payload.get("timestamp"));
    }

    @Test
    void sendFailurePropagatesForRuntimeHandling() throws Exception {
        MessageProducer producer = mock(MessageProducer.class);
        when(producer.send(anyString(), any(), any())).thenReturn(
                CompletableFuture.failedFuture(new RuntimeException("broker down")));

        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.INSERT);
        event.setTable("t");

        ChangeEventQueueSink sink = new ChangeEventQueueSink(producer, "cdc-t");
        assertThrows(Exception.class, () -> sink.invoke(event));
    }

    @Test
    void nullEventIgnoredAndArgumentsValidated() throws Exception {
        MessageProducer producer = mock(MessageProducer.class);
        ChangeEventQueueSink sink = new ChangeEventQueueSink(producer, "cdc-t");
        sink.invoke(null);
        verifyNoInteractions(producer);
        assertThrows(NullPointerException.class, () -> new ChangeEventQueueSink(null, "x"));
        assertThrows(NullPointerException.class, () -> new ChangeEventQueueSink(producer, null));
        assertThrows(IllegalArgumentException.class, () -> new ChangeEventQueueSink(producer, "x", 0));
    }
}
