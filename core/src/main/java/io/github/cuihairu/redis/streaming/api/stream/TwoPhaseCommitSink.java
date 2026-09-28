package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;

/**
 * Sink contract for two-phase-commit (2PC) end-to-end exactly-once semantics, Flink-style.
 *
 * <p>The runtime drives the following checkpoint flow for every sink that implements this
 * interface:</p>
 *
 * <ol>
 *   <li>{@link #beginTxn()} — lazily before the first element of a new transaction epoch.</li>
 *   <li>{@link #invoke(Object, Object)} — every element is buffered into the open transaction
 *       instead of being written directly to the external system.</li>
 *   <li>{@link #preCommit(Object)} — during the checkpoint, after the runtime paused the
 *       sources: the transaction's data is made recoverable (e.g. flushed to a staging area)
 *       but is not yet visible downstream. The runtime then serializes the transaction handle
 *       <em>into the checkpoint</em> ({@code storeCheckpoint(txn)}).</li>
 *   <li>{@link #commit(Object)} — after the checkpoint was stored successfully: finalize the
 *       transaction so its data becomes visible. The runtime then marks the checkpoint
 *       {@code sinkCommitted} and acknowledges the consumed records.</li>
 * </ol>
 *
 * <p>If the checkpoint fails after {@link #preCommit(Object)} the runtime calls
 * {@link #abort(Object)}; if the process dies after the checkpoint was stored but before
 * {@link #commit(Object)} ran, the recovery path loads the transaction handle from the
 * checkpoint and calls {@link #recoverAndCommit(Object)}. Handles of transactions whose
 * outcome is known to be unwanted (aborted checkpoints) are discarded through
 * {@link #recoverAndAbort(Object)}. Both recovery methods must tolerate being invoked for a
 * transaction that was already committed or aborted (the marker write and the commit are two
 * independent steps and may interleave with a crash).</p>
 *
 * <p>The transaction handle type must be {@link Serializable}: the runtime encodes the handle
 * (Java serialization) before storing it in the checkpoint snapshot, so complex handles are
 * allowed but must keep all referenced objects serializable too.</p>
 *
 * <p>All {@link CheckpointAwareSink} hooks remain available; implementations rarely need them
 * since the runtime drives the phases above.</p>
 *
 * @param <T>   The type of elements consumed
 * @param <Txn> The type of the transaction handle, serializable into a checkpoint
 */
public interface TwoPhaseCommitSink<T, Txn extends Serializable> extends CheckpointAwareSink<T> {

    /**
     * Starts a new transaction. Called by the runtime before the first element of an epoch is
     * delivered (and again after a checkpoint committed or aborted the previous transaction).
     */
    Txn beginTxn() throws Exception;

    /**
     * Buffers the element into the open transaction instead of writing it directly to the
     * external system.
     */
    void invoke(T value, Txn txn) throws Exception;

    /**
     * Never used for two-phase-commit sinks: the runtime routes elements through
     * {@link #invoke(Object, Object)}. Overrides {@link StreamSink#invoke(Object)} only to
     * keep the interface hierarchy, and fails fast if called directly.
     */
    @Override
    default void invoke(T value) throws Exception {
        throw new IllegalStateException(
                "TwoPhaseCommitSink must be driven via invoke(value, txn); plain StreamSink.invoke is not supported");
    }

    /**
     * Phase 1 of the commit: makes the transaction's data recoverable (durable or replayable)
     * without making it visible downstream. Called while the checkpoint is being taken; if
     * this throws, the checkpoint is aborted and {@link #abort(Object)} is called.
     */
    void preCommit(Txn txn) throws Exception;

    /**
     * Phase 2 of the commit: finalizes the transaction after the runtime stored the checkpoint
     * containing this transaction's handle. Must be idempotent enough to survive a retry after
     * a crash between the checkpoint store and this call (the recovery path may commit the
     * same handle again via {@link #recoverAndCommit(Object)}).
     */
    void commit(Txn txn) throws Exception;

    /**
     * Discards the open transaction because the checkpoint failed after
     * {@link #preCommit(Object)}. Defaults to {@link #recoverAndAbort(Object)} since most
     * implementations treat live and recovered aborts identically.
     */
    default void abort(Txn txn) throws Exception {
        recoverAndAbort(txn);
    }

    /**
     * Recovery compensation: commits a transaction whose handle was restored from a stored
     * checkpoint (the process may have died before {@link #commit(Object)} ran). Must be
     * idempotent.
     */
    Txn recoverAndCommit(Txn txn) throws Exception;

    /**
     * Recovery compensation: discards a transaction whose handle was restored from a
     * checkpoint that was rolled back. Must be idempotent.
     */
    Txn recoverAndAbort(Txn txn) throws Exception;
}
