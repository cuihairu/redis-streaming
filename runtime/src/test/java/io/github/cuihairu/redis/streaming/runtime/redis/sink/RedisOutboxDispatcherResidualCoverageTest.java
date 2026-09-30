package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.PendingEntry;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamPendingRangeArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.Codec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual-coverage regression for {@link RedisOutboxDispatcher}: the paths behind the
 * periodic loop rather than the happy round — convenience-constructor delegation, the
 * read/listPending/claim failure escapes, null-field entries, payload without an id
 * field, DLQ/ack/trim failure swallowing, and the runSafely catch that keeps the
 * scheduler alive when a round throws out of its internal catches.
 */
class RedisOutboxDispatcherResidualCoverageTest {

    private static final String OUTBOX_KEY = "test:job:outbox";
    private static final String DLQ_KEY = OUTBOX_KEY + ":dlq";
    private static final String GROUP = "outbox";
    private static final String CONSUMER = "relay";

    private RedissonClient redisson;
    private RStream<String, String> stream;
    private RStream<String, String> dlqStream;
    private RMap<String, String> epochs;
    private List<RedisOutboxDispatcher.Delivery<String>> delivered;
    private RedisOutboxDispatcher<String> dispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        stream = mock(RStream.class);
        dlqStream = mock(RStream.class);
        epochs = mock(RMap.class);
        when(redisson.<String, String>getStream(eq(OUTBOX_KEY), any(Codec.class))).thenReturn(stream);
        when(redisson.<String, String>getStream(eq(DLQ_KEY), any(Codec.class))).thenReturn(dlqStream);
        when(redisson.<String, String>getMap(eq(OUTBOX_KEY + ":epochs"), any(Codec.class))).thenReturn(epochs);
        delivered = new CopyOnWriteArrayList<>();
        dispatcher = new RedisOutboxDispatcher<>(redisson, OUTBOX_KEY, String.class, delivered::add,
                GROUP, CONSUMER, 100, 5, 30_000L, 60_000L, DLQ_KEY);
        when(epochs.get(anyString())).thenReturn(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<StreamMessageId, Map<String, String>> batch(Object... idAndFields) {
        Map<StreamMessageId, Map<String, String>> out = new LinkedHashMap<>();
        for (int i = 0; i < idAndFields.length; i += 2) {
            out.put((StreamMessageId) idAndFields[i], (Map<String, String>) idAndFields[i + 1]);
        }
        return out;
    }

    private static Map<String, String> fields(String epoch, String seq, String id, String payload) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(RedisOutboxSink.FIELD_EPOCH, epoch);
        f.put(RedisOutboxSink.FIELD_SEQ, seq);
        f.put(RedisOutboxSink.FIELD_ID, id);
        f.put(RedisOutboxSink.FIELD_PAYLOAD, payload);
        return f;
    }

    private static PendingEntry pending(StreamMessageId id, long deliveryCount, long idleMs) {
        return new PendingEntry(id, CONSUMER, idleMs, deliveryCount);
    }

    private void stubNoNewEntries() {
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class))).thenReturn(null);
    }

    @Test
    void convenienceConstructorDelegatesWithDefaultsAndExposesKeys() {
        RedisOutboxDispatcher<String> quick =
                new RedisOutboxDispatcher<>(redisson, OUTBOX_KEY, String.class, delivered::add);

        assertEquals(OUTBOX_KEY, quick.getOutboxKey());
        assertEquals(DLQ_KEY, quick.getDlqKey());
        quick.close();
    }

    @Test
    void readGroupFailureSkipsTheRoundButRetryPassStillRuns() {
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenThrow(new IllegalStateException("connection reset"));
        when(stream.listPending(any(StreamPendingRangeArgs.class))).thenReturn(List.of());

        assertDoesNotThrow(dispatcher::runOnce);

        verify(stream).listPending(any(StreamPendingRangeArgs.class));
        assertEquals(0, delivered.size());
    }

    @Test
    void listPendingFailureSkipsTheRetryPass() {
        stubNoNewEntries();
        when(stream.listPending(any(StreamPendingRangeArgs.class)))
                .thenThrow(new IllegalStateException("pending index unavailable"));

        assertDoesNotThrow(dispatcher::runOnce);

        assertEquals(0, delivered.size());
    }

    @Test
    void retryClaimFailureSkipsTheRetryPass() {
        stubNoNewEntries();
        when(stream.listPending(any(StreamPendingRangeArgs.class)))
                .thenReturn(List.of(pending(new StreamMessageId(4), 2, 40_000L)));
        when(stream.claim(eq(GROUP), eq(CONSUMER), eq(30_000L), eq(TimeUnit.MILLISECONDS), any()))
                .thenThrow(new IllegalStateException("claim rejected"));

        assertDoesNotThrow(dispatcher::runOnce);

        assertEquals(0, delivered.size());
        verify(stream, never()).ack(anyString(), any(StreamMessageId.class));
    }

    @Test
    void claimedUncommittedEntryStopsTheRetryLoop() {
        stubNoNewEntries();
        when(stream.listPending(any(StreamPendingRangeArgs.class)))
                .thenReturn(List.of(pending(new StreamMessageId(4), 2, 40_000L)));
        // claim succeeds but the epoch is still uncommitted: the retry loop must break
        // and keep the entry pending instead of spinning through the claimed batch
        when(stream.claim(eq(GROUP), eq(CONSUMER), eq(30_000L), eq(TimeUnit.MILLISECONDS), any()))
                .thenReturn(batch(new StreamMessageId(4), fields("e-unknown", "0", "r4",
                        "{\"id\":\"r4\",\"value\":\"v\"}")));

        dispatcher.runOnce();

        assertEquals(0, delivered.size());
        verify(stream, never()).ack(anyString(), any(StreamMessageId.class));
        verify(stream, never()).remove(any(StreamMessageId.class));
    }

    @Test
    void entryWithNullFieldsIsDiscardedAndAcked() {
        Map<StreamMessageId, Map<String, String>> entries = new LinkedHashMap<>();
        entries.put(new StreamMessageId(1), null);
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenReturn(entries);
        when(stream.listPending(any(StreamPendingRangeArgs.class))).thenReturn(List.of());

        dispatcher.runOnce();

        assertEquals(0, delivered.size());
        verify(stream).ack(GROUP, new StreamMessageId(1));
        verify(stream).remove(new StreamMessageId(1));
    }

    @Test
    void entryWithoutIdFieldResolvesNullRecordIdFromPayload() {
        when(epochs.get("e1")).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        Map<StreamMessageId, Map<String, String>> entries = new LinkedHashMap<>();
        entries.put(new StreamMessageId(1), Map.of(
                RedisOutboxSink.FIELD_EPOCH, "e1",
                RedisOutboxSink.FIELD_SEQ, "0",
                RedisOutboxSink.FIELD_PAYLOAD, "{\"value\":null}"));
        entries.put(new StreamMessageId(2), Map.of(
                RedisOutboxSink.FIELD_EPOCH, "e1",
                RedisOutboxSink.FIELD_SEQ, "1",
                RedisOutboxSink.FIELD_PAYLOAD, "{}"));
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenReturn(entries);
        when(stream.listPending(any(StreamPendingRangeArgs.class))).thenReturn(List.of());

        dispatcher.runOnce();

        assertEquals(2, delivered.size());
        // no id field in the fields map and no id in the payload: idText() must fall back to null
        assertNull(delivered.get(0).id());
        assertNull(delivered.get(0).value());   // "value": null payload arm
        assertNull(delivered.get(1).id());
        assertNull(delivered.get(1).value());   // missing "value" node arm
        assertEquals("e1", delivered.get(0).epoch());
    }

    @Test
    void dlqMoveFailureIsSwallowedAndKeepsTheRoundAlive() {
        stubNoNewEntries();
        StreamMessageId id1 = new StreamMessageId(9);
        when(stream.listPending(any(StreamPendingRangeArgs.class)))
                .thenReturn(List.of(pending(id1, 7, 40_000L))); // maxAttempts=5 → DLQ path
        when(stream.claim(eq(GROUP), eq(CONSUMER), eq(0L), eq(TimeUnit.MILLISECONDS), any()))
                .thenReturn(batch(id1, fields("e1", "3", "r9",
                        "{\"id\":\"r9\",\"value\":\"v\"}")));
        doThrow(new IllegalStateException("dlq write failed"))
                .when(dlqStream).add(any(org.redisson.api.stream.StreamAddArgs.class));

        assertDoesNotThrow(dispatcher::runOnce);

        assertEquals(0, delivered.size());
        verify(dlqStream).add(any(org.redisson.api.stream.StreamAddArgs.class));
    }

    @Test
    void ackAndTrimFailuresAreSwallowedAfterDelivery() {
        when(epochs.get("e1")).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenReturn(batch(new StreamMessageId(1), fields("e1", "0", "r1",
                        "{\"id\":\"r1\",\"value\":\"v\"}")));
        when(stream.listPending(any(StreamPendingRangeArgs.class))).thenReturn(List.of());
        doThrow(new IllegalStateException("ack rejected"))
                .when(stream).ack(eq(GROUP), any(StreamMessageId.class));
        doThrow(new IllegalStateException("trim rejected"))
                .when(stream).remove(any(StreamMessageId.class));

        assertDoesNotThrow(dispatcher::runOnce);

        assertEquals(1, delivered.size(), "delivery must succeed even when ack/trim fail");
        verify(stream).ack(GROUP, new StreamMessageId(1));
        verify(stream).remove(new StreamMessageId(1));
    }

    @Test
    void schedulerRoundFailureIsCaughtAndTheLoopKeepsRunning() throws Exception {
        // a round that throws out of its internal catches (epochs store down) must be
        // absorbed by runSafely, or the fixed-delay scheduler task would die silently
        when(epochs.get(anyString())).thenThrow(new IllegalStateException("epoch store down"));
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenReturn(batch(new StreamMessageId(1), fields("e1", "0", "r1",
                        "{\"id\":\"r1\",\"value\":\"v\"}")));
        when(stream.listPending(any(StreamPendingRangeArgs.class))).thenReturn(List.of());
        RedisOutboxDispatcher<String> fast = new RedisOutboxDispatcher<>(redisson, OUTBOX_KEY,
                String.class, delivered::add, GROUP, CONSUMER, 100, 5, 30_000L, 10L, DLQ_KEY);

        try {
            fast.start();
            Thread.sleep(250); // several fixed-delay rounds at a 10ms period
        } finally {
            fast.stop();
            fast.close();
        }

        verify(stream, atLeastOnce()).readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class));
        verify(epochs, atLeastOnce()).get(anyString());
    }
}
