package io.github.cuihairu.redis.streaming.api.stream;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link TwoPhaseCommitSink}: the runtime drives
 * beginTxn -> invoke -> preCommit -> storeCheckpoint(handle) -> commit, and
 * recoverAndCommit/recoverAndAbort are the crash compensations. A fake
 * {@link InMemoryTxnSink} with a staging buffer pins the visible-only-after-commit
 * semantics, the {@code abort} default delegating to {@link #recoverAndAbort}, and the
 * handle serialization contract the runtime relies on when storing the handle into a
 * checkpoint snapshot.
 */
class TwoPhaseCommitSinkTest {

    /** Transaction handle whose payload survives Java serialization unchanged. */
    static class StringTxn implements Serializable {
        private static final long serialVersionUID = 1L;
        final String id;
        StringTxn(String id) {
            this.id = id;
        }
    }

    /**
     * Fake 2PC sink: elements go into the open transaction's staging buffer and only become
     * part of {@link #committed} when commit/recoverAndCommit runs — the exact semantics the
     * runtime's preCommit -> storeCheckpoint -> commit flow must provide downstream systems.
     */
    static class InMemoryTxnSink implements TwoPhaseCommitSink<String, StringTxn> {
        final List<String> committed = new ArrayList<>();
        final List<String> abortedIds = new ArrayList<>();
        StringTxn openTxn;
        final List<String> staged = new ArrayList<>();
        int beginCalls;
        int preCommitCalls;

        @Override
        public StringTxn beginTxn() {
            beginCalls++;
            return new StringTxn("txn-" + beginCalls);
        }

        @Override
        public void invoke(String value, StringTxn txn) {
            assertEquals(openTxn, txn, "runtime must route elements into the open transaction");
            staged.add(value);
        }

        @Override
        public void preCommit(StringTxn txn) {
            preCommitCalls++;
            // data becomes durable-but-invisible: nothing is added to `committed` here
        }

        @Override
        public void commit(StringTxn txn) {
            committed.addAll(staged);
            staged.clear();
            openTxn = null;
        }

        @Override
        public StringTxn recoverAndCommit(StringTxn txn) {
            committed.addAll(staged);
            staged.clear();
            return txn;
        }

        @Override
        public StringTxn recoverAndAbort(StringTxn txn) {
            abortedIds.add(txn.id);
            staged.clear();
            return txn;
        }
    }

    @Test
    void happyPathBuffersElementsUntilCommit() throws Exception {
        InMemoryTxnSink sink = new InMemoryTxnSink();

        StringTxn txn = sink.beginTxn();
        sink.openTxn = txn;
        sink.invoke("a", txn);
        sink.invoke("b", txn);
        sink.preCommit(txn);

        assertTrue(sink.committed.isEmpty(),
                "preCommit alone must not make data visible downstream");

        sink.commit(txn);

        assertEquals(List.of("a", "b"), sink.committed);
        assertEquals(1, sink.beginCalls);
        assertEquals(1, sink.preCommitCalls);
    }

    @Test
    void abortDefaultDelegatesToRecoverAndAbort() throws Exception {
        InMemoryTxnSink sink = new InMemoryTxnSink();
        StringTxn txn = sink.beginTxn();
        sink.openTxn = txn;
        sink.invoke("x", txn);

        // no abort() override: the interface default must route into recoverAndAbort
        sink.abort(txn);

        assertEquals(List.of("txn-1"), sink.abortedIds);
        assertTrue(sink.staged.isEmpty(), "aborted transaction data must be discarded");
        assertTrue(sink.committed.isEmpty());
    }

    @Test
    void recoveryCompensationsAreUsableWithoutLiveTransactionState() throws Exception {
        // simulate crash after preCommit + checkpoint store: a fresh instance restores the
        // serialized handle and compensates without any open-transaction context
        InMemoryTxnSink sink = new InMemoryTxnSink();
        StringTxn txn = sink.beginTxn();
        sink.openTxn = txn;
        sink.invoke("pending", txn);
        sink.preCommit(txn);

        String encoded = encodeTxn(txn);
        StringTxn restored = decodeTxn(encoded);

        InMemoryTxnSink recovered = new InMemoryTxnSink();
        StringTxn committedTxn = recovered.recoverAndCommit(restored);
        assertNotNull(committedTxn);
        assertEquals("txn-1", committedTxn.id);
        // recovered instance has no staged elements; the compensation must not throw
        assertTrue(recovered.committed.isEmpty());

        InMemoryTxnSink rolledBack = new InMemoryTxnSink();
        StringTxn abortedTxn = rolledBack.recoverAndAbort(restored);
        assertEquals("txn-1", abortedTxn.id);
        assertEquals(List.of("txn-1"), rolledBack.abortedIds);
    }

    @Test
    void serializedHandleRoundTripsThroughCheckpointStyleEncoding() throws Exception {
        // the runtime stores the handle via Java serialization into the checkpoint snapshot;
        // the handle type must keep that encoding lossless
        StringTxn txn = new StringTxn("cp-42");

        String encoded = encodeTxn(txn);
        StringTxn back = decodeTxn(encoded);

        assertEquals("cp-42", back.id);
        assertEquals(txn.id, back.id);
    }

    @Test
    void twoPhaseCommitSinkIsACheckpointAwareSink() throws Exception {
        InMemoryTxnSink sink = new InMemoryTxnSink();
        assertTrue(sink instanceof CheckpointAwareSink<?>);
        assertTrue(sink instanceof StreamSink<?>);
        // inherited no-op checkpoint hooks must be callable without state
        sink.onCheckpointStart(1L);
        sink.onCheckpointComplete(1L);
        sink.onCheckpointAbort(1L, new RuntimeException("boom"));
        sink.onCheckpointRestore(1L);
        assertFalse(sink.committed.contains("never"));
    }

    @Test
    void plainInvokeIsBridgedToFailFast() {
        InMemoryTxnSink sink = new InMemoryTxnSink();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sink.invoke("direct"));
        assertTrue(ex.getMessage().contains("invoke(value, txn)"),
                "message must point at the two-arg entry point: " + ex.getMessage());
    }

    static String encodeTxn(Serializable txn) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(txn);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    @SuppressWarnings("unchecked")
    static <T> T decodeTxn(String encoded) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            return (T) in.readObject();
        }
    }
}
