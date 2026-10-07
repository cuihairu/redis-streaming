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
 * <p>Writes are routed to the NEXT epoch once {@link #prepareCommit()} has snapshotted the
 * open one: elements that arrive between prepare and commit/abort (a read that raced the
 * paused window, a drain timeout) must never join the epoch whose buffer was already
 * flushed — {@code commit} finalizes exactly the prepared snapshot, so a late element would
 * otherwise be silently dropped while its input was acked as processed. The epoch only
 * stops accepting new elements at prepare; it stays {@linkplain #hasOpenTxn() open} until
 * commit or abort finalizes it.</p>
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
    /** The epoch {@link #prepareCommit()} snapshotted, awaiting {@link #commit()}/{@link #abort()}. */
    private Serializable preparedTxn;

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
     * The epoch stops accepting elements — later {@link #invoke(Object)}s open a fresh
     * transaction — but stays {@link #hasOpenTxn() open} until {@link #commit()} or
     * {@link #abort()}.
     */
    String prepareCommit() throws Exception {
        if (preparedTxn != null) {
            // A previous checkpoint's commit phase threw and left its epoch prepared; the
            // runtime kept that checkpoint unmarked instead of aborting (its handles are
            // durably stored), so finalize it late — idempotent by contract, and exactly
            // what recovery would replay from the stored handle anyway.
            sink.commit(preparedTxn);
            preparedTxn = null;
        }
        ensureOpenTxn();
        sink.preCommit(openTxn);
        preparedTxn = openTxn;
        openTxn = null;
        return encodeTxn(preparedTxn);
    }

    /**
     * Phase 2: finalizes the transaction prepared by {@link #prepareCommit()} after the
     * runtime stored the checkpoint containing its handle. No-op when no epoch is open.
     * On a sink failure the epoch stays prepared: the handle is already durably stored, so
     * the retried phase 2 (next checkpoint) or the recovery path finalizes it.
     */
    void commit() throws Exception {
        if (preparedTxn == null) {
            return;
        }
        sink.commit(preparedTxn);
        preparedTxn = null;
    }

    /**
     * Discards the prepared epoch and any transaction opened since (checkpoint failed after
     * preCommit). No-op when no epoch is open; both epochs are closed even if the sink's
     * {@code abort} throws.
     */
    void abort() throws Exception {
        Exception failure = null;
        if (preparedTxn != null) {
            try {
                sink.abort(preparedTxn);
            } catch (Exception e) {
                failure = e;
            } finally {
                preparedTxn = null;
            }
        }
        if (openTxn != null) {
            try {
                sink.abort(openTxn);
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                }
            } finally {
                openTxn = null;
            }
        }
        if (failure != null) {
            throw failure;
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
        return preparedTxn != null || openTxn != null;
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
