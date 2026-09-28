package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.PendingEntry;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the async delivery side of the v2.5 outbox ({@link RedisOutboxDispatcher}).
 * Rounds are driven synchronously through the package-visible {@code runOnce()} against a
 * mocked Redisson client.
 */
class RedisOutboxDispatcherTest {

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
        // generous poll interval: start()/stop() must not actually loop during a test
        dispatcher = new RedisOutboxDispatcher<>(redisson, OUTBOX_KEY, String.class, delivered::add,
                GROUP, CONSUMER, 100, 5, 30_000L, 60_000L, DLQ_KEY);
        // epochs default: unknown (null) unless a test marks it
        when(epochs.get(anyString())).thenReturn(null);
    }

    private static Map<StreamMessageId, Map<String, String>> batch(Object... idAndFields) {
        Map<StreamMessageId, Map<String, String>> out = new LinkedHashMap<>();
        for (int i = 0; i < idAndFields.length; i += 2) {
            @SuppressWarnings("unchecked")
            Map<String, String> fields = (Map<String, String>) idAndFields[i + 1];
            out.put((StreamMessageId) idAndFields[i], fields);
        }
        return out;
    }

    private static Map<String, String> fields(String epoch, String seq, String id) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(RedisOutboxSink.FIELD_EPOCH, epoch);
        f.put(RedisOutboxSink.FIELD_SEQ, seq);
        f.put(RedisOutboxSink.FIELD_ID, id);
        f.put(RedisOutboxSink.FIELD_PAYLOAD, "{\"id\":\"" + id + "\",\"value\":\"v-" + id + "\"}");
        return f;
    }

    private void stubNewEntries(Map<StreamMessageId, Map<String, String>> entries) {
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class))).thenReturn(entries);
    }

    private void stubNoNewEntries() {
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class))).thenReturn(null);
    }

    private void stubPending(List<PendingEntry> pending) {
        when(stream.listPending(any(StreamPendingRangeArgs.class))).thenReturn(pending);
    }

    private static PendingEntry pending(StreamMessageId id, long deliveryCount, long idleMs) {
        return new PendingEntry(id, CONSUMER, idleMs, deliveryCount);
    }

    @Test
    void committedEntriesAreDeliveredInOrderThenAckedAndTrimmed() {
        when(epochs.get("e1")).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        StreamMessageId id1 = new StreamMessageId(1);
        StreamMessageId id2 = new StreamMessageId(2);
        stubNewEntries(batch(id1, fields("e1", "0", "r1"), id2, fields("e1", "1", "r2")));

        dispatcher.runOnce();

        assertEquals(List.of("r1", "r2"), delivered.stream().map(RedisOutboxDispatcher.Delivery::id).toList());
        assertEquals("e1", delivered.get(0).epoch());
        assertEquals("v-r1", delivered.get(0).value());
        verify(stream).ack(GROUP, id1);
        verify(stream).ack(GROUP, id2);
        verify(stream).remove(id1);
        verify(stream).remove(id2);
    }

    @Test
    void uncommittedEpochStopsTheRoundAtHeadOfLine() {
        when(epochs.get("e1")).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        StreamMessageId id1 = new StreamMessageId(1);
        StreamMessageId id2 = new StreamMessageId(2);
        stubNewEntries(batch(id1, fields("e1", "0", "r1"),
                id2, fields("e2-unknown", "0", "r2")));

        dispatcher.runOnce();

        // e1 delivered; e2 stays pending (no ack, no trim) until its epoch is committed
        assertEquals(1, delivered.size());
        verify(stream).ack(GROUP, id1);
        verify(stream, never()).ack(GROUP, id2);
        verify(stream, never()).remove(id2);
    }

    @Test
    void abortedEpochEntriesAreDiscardedWithoutDelivery() {
        when(epochs.get("e1")).thenReturn(RedisOutboxSink.STATUS_ABORTED);
        StreamMessageId id1 = new StreamMessageId(1);
        stubNewEntries(batch(id1, fields("e1", "0", "r1")));

        dispatcher.runOnce();

        assertEquals(0, delivered.size());
        verify(stream).ack(GROUP, id1);
        verify(stream).remove(id1);
    }

    @Test
    void entryWithoutEpochFieldIsDiscarded() {
        StreamMessageId id1 = new StreamMessageId(1);
        stubNewEntries(batch(id1, Map.of("garbage", "1")));

        dispatcher.runOnce();

        assertEquals(0, delivered.size());
        verify(stream).ack(GROUP, id1);
        verify(stream).remove(id1);
    }

    @Test
    void failingDeliveryStopsTheRoundAndKeepsEverythingPending() {
        when(epochs.get("e1")).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        List<RedisOutboxDispatcher.Delivery<String>> failing = new CopyOnWriteArrayList<>();
        RedisOutboxDispatcher<String> failingDispatcher = new RedisOutboxDispatcher<>(redisson, OUTBOX_KEY,
                String.class, d -> {
                    if (d.id().equals("r1")) throw new IllegalStateException("target down");
                    failing.add(d);
                }, GROUP, CONSUMER, 100, 5, 30_000L, 60_000L, DLQ_KEY);
        StreamMessageId id1 = new StreamMessageId(1);
        StreamMessageId id2 = new StreamMessageId(2);
        stubNewEntries(batch(id1, fields("e1", "0", "r1"), id2, fields("e1", "1", "r2")));

        failingDispatcher.runOnce();

        assertEquals(0, failing.size(), "later entries must not be attempted after a failure");
        verify(stream, never()).ack(GROUP, id1);
        verify(stream, never()).remove(id1);
        verify(stream, never()).ack(GROUP, id2);
    }

    @Test
    void entryOverMaxAttemptsIsMovedToDlqNotDelivered() {
        stubNoNewEntries();
        StreamMessageId id1 = new StreamMessageId(9);
        stubPending(List.of(pending(id1, 6, 40_000L))); // maxAttempts=5, idle past retry
        Map<StreamMessageId, Map<String, String>> bodies = batch(id1, fields("e1", "3", "r9"));
        when(stream.claim(eq(GROUP), eq(CONSUMER), eq(0L), eq(TimeUnit.MILLISECONDS), any()))
                .thenReturn(bodies);

        dispatcher.runOnce();

        assertEquals(0, delivered.size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<StreamAddArgs> dlqAdd = ArgumentCaptor.forClass(StreamAddArgs.class);
        verify(dlqStream).add(dlqAdd.capture());
        verify(stream).ack(GROUP, id1);
        verify(stream).remove(id1);
    }

    @Test
    void idlePendingEntryIsReclaimedAndRedelivered() {
        when(epochs.get("e1")).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        stubNoNewEntries();
        StreamMessageId id1 = new StreamMessageId(4);
        stubPending(List.of(pending(id1, 2, 40_000L))); // idle 40s >= retryIdle 30s
        when(stream.claim(eq(GROUP), eq(CONSUMER), eq(30_000L), eq(TimeUnit.MILLISECONDS),
                any()))
                .thenReturn(batch(id1, fields("e1", "0", "r1")));

        dispatcher.runOnce();

        assertEquals(1, delivered.size());
        assertEquals("r1", delivered.get(0).id());
        verify(stream).ack(GROUP, id1);
        verify(stream).remove(id1);
    }

    @Test
    void pendingEntryBelowIdleThresholdIsLeftAlone() {
        stubNoNewEntries();
        StreamMessageId id1 = new StreamMessageId(4);
        stubPending(List.of(pending(id1, 1, 1_000L))); // idle 1s < retryIdle 30s

        dispatcher.runOnce();

        assertEquals(0, delivered.size());
        verify(stream, never()).claim(anyString(), anyString(), anyLong(), any(TimeUnit.class),
                any());
        verify(stream, never()).ack(GROUP, id1);
    }

    @Test
    void dlqEntryWithoutClaimableBodyStillAcks() {
        stubNoNewEntries();
        StreamMessageId id1 = new StreamMessageId(9);
        stubPending(List.of(pending(id1, 7, 40_000L)));
        when(stream.claim(eq(GROUP), eq(CONSUMER), eq(0L), eq(TimeUnit.MILLISECONDS), any()))
                .thenReturn(Map.of()); // body vanished (already trimmed)

        dispatcher.runOnce();

        verify(dlqStream).add(any(StreamAddArgs.class));
        verify(stream).ack(GROUP, id1);
    }

    @Test
    void startCreatesGroupAndToleratesBusyGroupThenStopsCleanly() {
        stubNoNewEntries();
        stubPending(List.of());
        dispatcher.start();
        assertDoesNotThrow(dispatcher::start); // BUSYGROUP path: createGroup throws
        dispatcher.stop();
        assertDoesNotThrow(dispatcher::close); // idempotent stop
        verify(stream, atLeastOnce()).createGroup(any(StreamCreateGroupArgs.class));
    }

    @Test
    void startWithThrowingGroupCreationStillBootsTheLoop() {
        doThrow(new IllegalStateException("BUSYGROUP")).when(stream).createGroup(any(StreamCreateGroupArgs.class));
        stubNoNewEntries();
        stubPending(List.of());
        try {
            dispatcher.start();
        } finally {
            dispatcher.stop();
        }
        // createGroup failure must not prevent the dispatcher from running rounds
        verify(stream, times(1)).createGroup(any(StreamCreateGroupArgs.class));
    }
}
