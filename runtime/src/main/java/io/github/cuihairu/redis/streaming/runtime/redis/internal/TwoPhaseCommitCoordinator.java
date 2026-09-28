package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.stream.TwoPhaseCommitSink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Base64;
import java.util.Objects;

/**
 * Drives one {@link TwoPhaseCommitSink} through the runtime side of the two-phase-commit
 * protocol. The coordinator owns the sink's currently open transaction (lazily begun on the
 * first element of an epoch), routes elements into it, and produces the Base64-encoded
 * serializable transaction handle that the checkpoint manager stores
 * <em>before</em> {@link #commit()} finalizes the transaction:
 *
 * <pre>beginTxn -> invoke(value, txn)* -> [checkpoint] prepareCommit() -> store handle ->
 * commit()   // normal path
 *          \-> abort()      // checkpoint failed after preCommit
 * recovery: recoverAndCommit(handle) / recoverAndAbort(handle)</pre>
 *
 * <p>The handle encoding is deliberately storage-agnostic (Java serialization + Base64
 * string) so it can live in any checkpoint snapshot — in-memory or Redis-backed.</p>
 *
 * <p>This class is not thread-safe: the runner invokes it from its single processing thread,
 * and the environment drives the checkpoint phases between messages.</p>
 */
final class TwoPhaseCommitCoordinator {

    private final TwoPhaseCommitSink<Object, Serializable> sink;
    private Serializable openTxn;

    TwoPhaseCommitCoordinator(TwoPhaseCommitSink<?, ?> sink) {
        this.sink = cast(Objects.requireNonNull(sink, "sink"));
    }

    /**
     * Delivers one element into the open transaction, beginning the transaction lazily if
     * this is the first element of the epoch.
     */
    void invoke(Object value) throws Exception {
        ensureOpenTxn();
        sink.invoke(value, openTxn);
    }

    /**
     * Phase 1: pre-commits the open transaction (empty epochs included, so the handle is
     * always checkpointed) and returns the encoded handle for the checkpoint snapshot.
     * The transaction stays open until {@link #commit()} or {@link #abort()}.
     */
    String prepareCommit() throws Exception {
        ensureOpenTxn();
        sink.preCommit(openTxn);
        return encodeTxn(openTxn);
    }

    /**
     * Phase 2: finalizes the open transaction after the runtime stored the checkpoint
     * containing {@link #prepareCommit()}'s handle. No-op when no epoch is open.
     */
    void commit() throws Exception {
        if (openTxn == null) {
            return;
        }
        sink.commit(openTxn);
        openTxn = null;
    }

    /**
     * Discards the open transaction (checkpoint failed after preCommit). No-op when no epoch
     * is open; the epoch is closed even if the sink's {@code abort} throws.
     */
    void abort() throws Exception {
        if (openTxn == null) {
            return;
        }
        try {
            sink.abort(openTxn);
        } finally {
            openTxn = null;
        }
    }

    /**
     * Recovery compensation: commits the transaction restored from a stored checkpoint whose
     * {@code sinkCommitted} marker is missing (crash between store and commit).
     */
    Object recoverAndCommit(String encodedTxn) throws Exception {
        return sink.recoverAndCommit((Serializable) decodeTxn(encodedTxn));
    }

    /**
     * Recovery compensation: discards the transaction restored from a rolled-back checkpoint.
     */
    Object recoverAndAbort(String encodedTxn) throws Exception {
        return sink.recoverAndAbort((Serializable) decodeTxn(encodedTxn));
    }

    boolean hasOpenTxn() {
        return openTxn != null;
    }

    private void ensureOpenTxn() throws Exception {
        if (openTxn == null) {
            openTxn = sink.beginTxn();
        }
    }

    /**
     * Encodes a transaction handle for checkpoint storage: Java serialization + Base64.
     * The handle type is {@code Txn extends Serializable} by the {@link TwoPhaseCommitSink}
     * contract, so the cast cannot fail for a well-formed sink.
     */
    static String encodeTxn(Serializable txn) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(txn);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /**
     * Decodes a handle stored in a checkpoint snapshot (inverse of {@link #encodeTxn}).
     */
    static Object decodeTxn(String encodedTxn) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(encodedTxn)))) {
            return in.readObject();
        }
    }

    @SuppressWarnings("unchecked")
    private static TwoPhaseCommitSink<Object, Serializable> cast(TwoPhaseCommitSink<?, ?> sink) {
        return (TwoPhaseCommitSink<Object, Serializable>) sink;
    }
}
