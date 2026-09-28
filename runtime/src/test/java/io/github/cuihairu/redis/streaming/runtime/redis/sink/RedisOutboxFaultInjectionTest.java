package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.IdempotentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.PendingEntry;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamPendingRangeArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.Codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fault-injection tests for the v2.5 outbox: each test replays a crash window from the
 * docs/exactly-once.md matrix against mock Redis and verifies that recovery (or discard)
 * happens through the epoch markers, i.e. the checkpoint only has to keep the outbox
 * records from being lost.
 */
class RedisOutboxFaultInjectionTest {

    private static final String OUTBOX_KEY = "fault:job:outbox";
    private static final String DLQ_KEY = OUTBOX_KEY + ":dlq";
    private static final String GROUP = "outbox";
    private static final String CONSUMER = "relay";

    private RedissonClient redisson;
    private RStream<String, String> stream;
    private RStream<String, String> dlqStream;
    private RMap<String, String> epochs;
    private List<RedisOutboxDispatcher.Delivery<String>> delivered;

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
    }

    /* ---------- helpers mirroring the runtime's handle encoding ---------- */

    private static String encodeHandle(RedisOutboxSink.OutboxTxn txn) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(txn);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private static RedisOutboxSink.OutboxTxn decodeHandle(String handle) throws Exception {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(handle)))) {
            return (RedisOutboxSink.OutboxTxn) ois.readObject();
        }
    }

    private RedisOutboxSink<String> newSink() {
        return new RedisOutboxSink<>(redisson, OUTBOX_KEY, new ObjectMapper());
    }

    private RedisOutboxDispatcher<String> newDispatcher() {
        return new RedisOutboxDispatcher<>(redisson, OUTBOX_KEY, String.class, delivered::add,
                GROUP, CONSUMER, 100, 5, 30_000L, 60_000L, DLQ_KEY);
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

    private void stubNoPending() {
        when(stream.listPending(any(StreamPendingRangeArgs.class))).thenReturn(List.of());
    }

    /* ---------- crash window tests ---------- */

    /**
     * Checkpoint stored (txn handle inside), commit never executed, process died.
     * Recovery must mark the epoch committed from the stored handle so the dispatcher
     * delivers: no data loss.
     */
    @Test
    void crashAfterCheckpointStoreBeforeCommitIsRecoveredByRecoverAndCommit() throws Exception {
        RedisOutboxSink<String> sink = newSink();
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.invoke(new IdempotentRecord<>("r1", "a"), txn);
        sink.invoke(new IdempotentRecord<>("r2", "b"), txn);
        sink.preCommit(txn); // staged into the outbox stream, invisible (no marker)
        String handle = encodeHandle(txn);  // ...stored into the checkpoint
        // --- process dies here; commit never ran ---

        // restart: fresh sink instance, handle restored from the checkpoint
        RedisOutboxSink<String> restarted = newSink();
        restarted.recoverAndCommit(decodeHandle(handle));
        verify(epochs).put(txn.epoch(), RedisOutboxSink.STATUS_COMMITTED);

        when(epochs.get(txn.epoch())).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        StreamMessageId id1 = new StreamMessageId(1);
        StreamMessageId id2 = new StreamMessageId(2);
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenReturn(batch(id1, fields(txn.epoch(), "0", "r1"), id2, fields(txn.epoch(), "1", "r2")));
        stubNoPending();

        newDispatcher().runOnce();

        assertEquals(List.of("r1", "r2"), delivered.stream().map(RedisOutboxDispatcher.Delivery::id).toList());
        verify(stream).ack(GROUP, id1);
        verify(stream).ack(GROUP, id2);
    }

    /**
     * Commit executed, process died before the checkpoint was marked sinkCommitted.
     * The recovery replays recoverAndCommit (idempotent HSET) and the dispatcher has
     * already trimmed the delivered entries — no duplicate delivery inside Redis.
     */
    @Test
    void commitWithoutSinkCommittedMarkerIsIdempotentAndDeliversExactlyOnce() throws Exception {
        RedisOutboxSink<String> sink = newSink();
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.invoke(new IdempotentRecord<>("r1", "a"), txn);
        sink.preCommit(txn);
        String handle = encodeHandle(txn);
        sink.commit(txn); // --- dies right after ---

        RedisOutboxSink<String> restarted = newSink();
        restarted.recoverAndCommit(decodeHandle(handle)); // replay: same marker again

        when(epochs.get(txn.epoch())).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        StreamMessageId id1 = new StreamMessageId(1);
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenReturn(batch(id1, fields(txn.epoch(), "0", "r1")))
                .thenReturn(null); // entries were trimmed after the first delivery
        stubNoPending();

        RedisOutboxDispatcher<String> dispatcher = newDispatcher();
        dispatcher.runOnce();
        dispatcher.runOnce();

        assertEquals(1, delivered.size(), "trimmed entries must not redeliver on the next round");
        verify(stream, times(1)).ack(GROUP, id1);
    }

    /** Aborted checkpoint: staged records must be discarded, never delivered. */
    @Test
    void abortedEpochIsDiscardedByTheDispatcher() throws Exception {
        RedisOutboxSink<String> sink = newSink();
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.invoke(new IdempotentRecord<>("r1", "a"), txn);
        sink.preCommit(txn);
        sink.abort(txn); // checkpoint aborted after preCommit
        verify(epochs).put(txn.epoch(), RedisOutboxSink.STATUS_ABORTED);

        when(epochs.get(txn.epoch())).thenReturn(RedisOutboxSink.STATUS_ABORTED);
        StreamMessageId id1 = new StreamMessageId(1);
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenReturn(batch(id1, fields(txn.epoch(), "0", "r1")));
        stubNoPending();

        newDispatcher().runOnce();

        assertEquals(0, delivered.size());
        verify(stream).ack(GROUP, id1);
        verify(stream).remove(id1);
        verify(dlqStream, never()).add(any(StreamAddArgs.class));
    }

    /**
     * preCommit fails midway (some entries flushed): the runtime aborts the epoch, the
     * half-prepared remnant is discarded like any aborted epoch.
     */
    @Test
    void partialPreCommitFailureThenAbortDiscardsTheFlushedRemnant() throws Exception {
        RedisOutboxSink<String> sink = newSink();
        RedisOutboxSink.OutboxTxn txn = sink.beginTxn();
        sink.invoke(new IdempotentRecord<>("r1", "a"), txn);
        sink.invoke(new IdempotentRecord<>("r2", "b"), txn);

        Mockito.doThrow(new IllegalStateException("redis write failed"))
                .when(stream).add(any(StreamAddArgs.class));

        try {
            sink.preCommit(txn);
        } catch (IllegalStateException expected) {
            // the runtime sees this and aborts the epoch
        }
        sink.recoverAndAbort(txn);

        when(epochs.get(txn.epoch())).thenReturn(RedisOutboxSink.STATUS_ABORTED);
        StreamMessageId id1 = new StreamMessageId(1);
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenReturn(batch(id1, fields(txn.epoch(), "0", "r1")));
        stubNoPending();

        newDispatcher().runOnce();

        assertEquals(0, delivered.size(), "a half-prepared epoch must never deliver");
        verify(stream).ack(GROUP, id1);
    }

    /**
     * Epoch committed later than an already-committed older epoch (e.g. recovered on a
     * delayed restart): the dispatcher waits at the head of line until the marker lands,
     * then delivers in commit order.
     */
    @Test
    void dispatcherWaitsHeadOfLineUntilALateEpochIsRecovered() throws Exception {
        when(epochs.get(anyString())).thenReturn(null); // neither epoch committed yet
        StreamMessageId id1 = new StreamMessageId(1);
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class)))
                .thenReturn(batch(id1, fields("e-late", "0", "r1")));
        stubNoPending();

        RedisOutboxDispatcher<String> dispatcher = newDispatcher();
        dispatcher.runOnce();
        assertEquals(0, delivered.size(), "uncommitted epoch must wait");
        verify(stream, never()).ack(GROUP, id1);

        // the delayed recovery commits the epoch; the next round delivers
        newSink().recoverAndCommit(new RedisOutboxSink.OutboxTxn("e-late"));
        when(epochs.get("e-late")).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        dispatcher.runOnce();

        assertEquals(1, delivered.size());
        verify(stream).ack(GROUP, id1);
    }

    /**
     * Dispatcher died between delivery and ack in a previous process: the entry sits in
     * the pending list and is reclaimed after the idle threshold — at-least-once into
     * the target, dedup is the target's job (stable record id).
     */
    @Test
    void dispatcherCrashBetweenDeliverAndAckRedeliversFromPending() {
        when(epochs.get("e1")).thenReturn(RedisOutboxSink.STATUS_COMMITTED);
        when(stream.readGroup(eq(GROUP), eq(CONSUMER), any(StreamReadGroupArgs.class))).thenReturn(null);
        StreamMessageId id1 = new StreamMessageId(3);
        when(stream.listPending(any(StreamPendingRangeArgs.class)))
                .thenReturn(List.<PendingEntry>of(new PendingEntry(id1, CONSUMER, 40_000L, 1)));
        when(stream.claim(eq(GROUP), eq(CONSUMER), eq(30_000L), eq(TimeUnit.MILLISECONDS),
                any()))
                .thenReturn(batch(id1, fields("e1", "0", "r1")));

        newDispatcher().runOnce();

        assertEquals(1, delivered.size());
        assertEquals("r1", delivered.get(0).id());
        verify(stream).ack(GROUP, id1);
        verify(stream).remove(id1);
    }
}
