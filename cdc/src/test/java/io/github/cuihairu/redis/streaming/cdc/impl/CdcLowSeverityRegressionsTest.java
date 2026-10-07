package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;
import io.github.cuihairu.redis.streaming.cdc.mq.ChangeEventQueueSink;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regressions for the CDC low-severity audit items: malformed binlog positions
 * must fail loudly instead of crashing with AIOOBE or being ignored (CDC-L5),
 * a timed-out sink send must be cancelled so retries cannot duplicate the event
 * (CDC-L6), and ChangeEvent must survive Java serialization (CDC-L9).
 */
class CdcLowSeverityRegressionsTest {

    @Test
    void malformedCommitPositionFailsLoudlyNotWithAIOOBE() throws Exception {
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("cdc-l5").username("u").password("p").build());

        // trailing colon: the old split(":")+parts[1] crashed with a bare ArrayIndexOutOfBounds
        IllegalArgumentException trailingColon = assertThrows(IllegalArgumentException.class,
                () -> connector.doCommit("mysql-bin.000004:"));
        assertTrue(trailingColon.getMessage().contains("filename:offset"));

        // no colon at all: the old code silently ignored the commit
        IllegalArgumentException noColon = assertThrows(IllegalArgumentException.class,
                () -> connector.doCommit("mysql-bin.000004"));
        assertTrue(noColon.getMessage().contains("filename:offset"));

        // non-numeric offset: rejected up front
        assertThrows(IllegalArgumentException.class, () -> connector.doCommit("mysql-bin.000004:abc"));

        // valid position: accepted without throwing
        assertDoesNotThrow(() -> connector.doCommit("mysql-bin.000004:8200"));
        // null stays a no-op (unchanged contract)
        assertDoesNotThrow(() -> connector.doCommit(null));
    }

    @Test
    void malformedResetPositionFailsLoudly() throws Exception {
        MySQLBinlogCDCConnector connector = new MySQLBinlogCDCConnector(
                CDCConfigurationBuilder.forMySQLBinlog("cdc-l5-reset").username("u").password("p").build());

        assertThrows(IllegalArgumentException.class, () -> connector.doResetToPosition("mysql-bin.000004:"));
        // null/empty stay no-ops (unchanged contract: nothing to reconnect to)
        assertDoesNotThrow(() -> connector.doResetToPosition(null));
        assertDoesNotThrow(() -> connector.doResetToPosition(""));
    }

    @Test
    void timedOutSinkSendIsCancelledSoRetryCannotDuplicate() throws Exception {
        MessageProducer producer = mock(MessageProducer.class);
        AtomicReference<CompletableFuture<String>> sent = new AtomicReference<>();
        when(producer.send(anyString(), any(), any())).thenAnswer(inv -> {
            CompletableFuture<String> never = new CompletableFuture<>();
            sent.set(never);
            return never;
        });
        ChangeEventQueueSink sink = new ChangeEventQueueSink(producer, "cdc-topic", 1);

        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.INSERT);
        event.setTable("users");
        event.setKey("1");

        assertThrows(TimeoutException.class, () -> sink.invoke(event));
        assertTrue(sent.get().isCancelled(),
                "the in-flight send must be cancelled on timeout so a retry cannot deliver the event twice");

        // a completing send still works end to end
        when(producer.send(anyString(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture("id-1"));
        assertDoesNotThrow(() -> sink.invoke(event));
    }

    @Test
    void changeEventSurvivesJavaSerialization() throws Exception {
        ChangeEvent event = new ChangeEvent();
        event.setEventType(ChangeEvent.EventType.UPDATE);
        event.setDatabase("shop");
        event.setTable("orders");
        event.setKey("42");
        event.setBeforeData(new HashMap<>(java.util.Map.of("status", "new")));
        event.setAfterData(new HashMap<>(java.util.Map.of("status", "paid")));
        event.setTimestamp(Instant.ofEpochMilli(1234567890L));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(event);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ChangeEvent restored = (ChangeEvent) in.readObject();
            assertEquals(ChangeEvent.EventType.UPDATE, restored.getEventType());
            assertEquals("shop", restored.getDatabase());
            assertEquals("orders", restored.getTable());
            assertEquals("42", restored.getKey());
            assertEquals("paid", restored.getAfterData().get("status"));
            assertEquals(Instant.ofEpochMilli(1234567890L), restored.getTimestamp());
        }
    }
}
