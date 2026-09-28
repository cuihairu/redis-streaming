package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.stream.TwoPhaseCommitSink;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link TwoPhaseCommitCoordinator}: lazy transaction begin, element
 * routing, the prepare/commit/abort epoch lifecycle, and the recovery compensations with
 * Base64-encoded handles. No Redis required.
 */
class TwoPhaseCommitCoordinatorTest {

    static class Txn implements Serializable {
        private static final long serialVersionUID = 1L;
        final long seq;
        Txn(long seq) {
            this.seq = seq;
        }
    }

    /** Fake 2PC sink with a staging buffer that only becomes visible on commit. */
    static class FakeSink implements TwoPhaseCommitSink<String, Txn> {
        final List<String> committed = new ArrayList<>();
        final List<Long> aborted = new ArrayList<>();
        final List<Long> preCommitted = new ArrayList<>();
        final List<Txn> committedHandles = new ArrayList<>();
        final List<Txn> invokedHandles = new ArrayList<>();
        long beginCounter;
        Txn openTxn;
        final List<String> staged = new ArrayList<>();
        RuntimeException abortFailure;

        @Override
        public Txn beginTxn() {
            return new Txn(++beginCounter);
        }

        @Override
        public void invoke(String value, Txn txn) {
            invokedHandles.add(txn);
            if (openTxn == null) {
                openTxn = txn;
            }
            staged.add(value);
        }

        @Override
        public void preCommit(Txn txn) {
            preCommitted.add(txn.seq);
        }

        @Override
        public void commit(Txn txn) {
            committed.addAll(staged);
            staged.clear();
            committedHandles.add(txn);
            openTxn = null;
        }

        @Override
        public Txn recoverAndCommit(Txn txn) {
            committedHandles.add(txn);
            return txn;
        }

        @Override
        public Txn recoverAndAbort(Txn txn) {
            aborted.add(txn.seq);
            return txn;
        }

        @Override
        public void abort(Txn txn) {
            if (abortFailure != null) {
                throw abortFailure;
            }
            aborted.add(txn.seq);
            staged.clear();
        }
    }

    @Test
    void txnIsBegunLazilyOnFirstElement() throws Exception {
        FakeSink sink = new FakeSink();
        TwoPhaseCommitCoordinator coordinator = new TwoPhaseCommitCoordinator(sink);

        assertFalse(coordinator.hasOpenTxn(), "no txn before the first element");
        coordinator.invoke("a");
        assertTrue(coordinator.hasOpenTxn());
        assertEquals(1, sink.invokedHandles.size());

        coordinator.invoke("b");
        assertEquals(2, sink.invokedHandles.size());
        assertSame(sink.invokedHandles.get(0), sink.invokedHandles.get(1),
                "both elements of an epoch share one transaction");
    }

    @Test
    void prepareCommitPreCommitsAndReturnsEncodableHandle() throws Exception {
        FakeSink sink = new FakeSink();
        TwoPhaseCommitCoordinator coordinator = new TwoPhaseCommitCoordinator(sink);
        coordinator.invoke("a");

        String handle = coordinator.prepareCommit();

        assertEquals(List.of(1L), sink.preCommitted);
        assertTrue(coordinator.hasOpenTxn(), "epoch stays open between prepare and commit");
        Txn decoded = (Txn) TwoPhaseCommitCoordinator.decodeTxn(handle);
        assertEquals(1L, decoded.seq);
        assertTrue(sink.committed.isEmpty(), "preCommit must not make data visible");
    }

    @Test
    void emptyEpochIsStillPreCommittedSoHandleIsCheckpointed() throws Exception {
        FakeSink sink = new FakeSink();
        TwoPhaseCommitCoordinator coordinator = new TwoPhaseCommitCoordinator(sink);

        String handle = coordinator.prepareCommit();

        assertTrue(coordinator.hasOpenTxn(), "lazy begin also happens at prepare time");
        assertEquals(List.of(1L), sink.preCommitted);
        assertEquals(1L, ((Txn) TwoPhaseCommitCoordinator.decodeTxn(handle)).seq);
    }

    @Test
    void commitFinalizesStagedDataAndClosesEpoch() throws Exception {
        FakeSink sink = new FakeSink();
        TwoPhaseCommitCoordinator coordinator = new TwoPhaseCommitCoordinator(sink);
        coordinator.invoke("a");
        coordinator.invoke("b");
        coordinator.prepareCommit();

        coordinator.commit();

        assertEquals(List.of("a", "b"), sink.committed);
        assertEquals(1, sink.committedHandles.size());
        assertFalse(coordinator.hasOpenTxn(), "epoch closed after commit");
        // commit with no open epoch is a no-op (idempotent env replay safety)
        coordinator.commit();
        assertEquals(1, sink.committedHandles.size());
    }

    @Test
    void abortDiscardsStagedDataAndClosesEpoch() throws Exception {
        FakeSink sink = new FakeSink();
        TwoPhaseCommitCoordinator coordinator = new TwoPhaseCommitCoordinator(sink);
        coordinator.invoke("a");
        coordinator.prepareCommit();

        coordinator.abort();

        assertEquals(List.of(1L), sink.aborted);
        assertTrue(sink.staged.isEmpty(), "aborted data must be discarded");
        assertTrue(sink.committed.isEmpty());
        assertFalse(coordinator.hasOpenTxn());
    }

    @Test
    void abortClosesEpochEvenWhenSinkThrows() throws Exception {
        FakeSink sink = new FakeSink();
        TwoPhaseCommitCoordinator coordinator = new TwoPhaseCommitCoordinator(sink);
        coordinator.invoke("a");
        coordinator.prepareCommit();

        sink.abortFailure = new RuntimeException("abort failed");
        assertThrows(RuntimeException.class, coordinator::abort);
        assertFalse(coordinator.hasOpenTxn(), "epoch is closed even if abort throws");
        assertTrue(sink.committed.isEmpty());
        assertTrue(sink.aborted.isEmpty(), "the sink threw before recording the abort");

        // abort with no open epoch is a coordinator-level no-op: even though the sink's
        // abort is still primed to throw, the coordinator must not reach it again
        assertDoesNotThrow(coordinator::abort);
        assertTrue(sink.aborted.isEmpty());
    }

    @Test
    void recoverCompensationsDecodeTheStoredHandleAndDelegate() throws Exception {
        FakeSink sink = new FakeSink();
        TwoPhaseCommitCoordinator coordinator = new TwoPhaseCommitCoordinator(sink);
        coordinator.invoke("pending");
        String handle = coordinator.prepareCommit();

        Txn committed = (Txn) coordinator.recoverAndCommit(handle);
        assertEquals(1L, committed.seq);
        assertEquals(1, sink.committedHandles.size(),
                "recoverAndCommit must reach the sink with the decoded handle");

        Txn aborted = (Txn) coordinator.recoverAndAbort(handle);
        assertEquals(1L, aborted.seq);
        assertEquals(List.of(1L), sink.aborted);
    }

    @Test
    void handleEncodingIsLosslessThroughBase64AndJavaSerialization() throws Exception {
        Txn txn = new Txn(42);
        String encoded = TwoPhaseCommitCoordinator.encodeTxn(txn);
        Txn back = (Txn) TwoPhaseCommitCoordinator.decodeTxn(encoded);
        assertEquals(42L, back.seq);
    }

    @Test
    void coordinatorRejectsNullSink() {
        assertThrows(NullPointerException.class, () -> new TwoPhaseCommitCoordinator(null));
    }
}
