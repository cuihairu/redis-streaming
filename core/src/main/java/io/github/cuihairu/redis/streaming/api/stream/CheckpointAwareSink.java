package io.github.cuihairu.redis.streaming.api.stream;

/**
 * Optional sink hooks for checkpoint coordination.
 *
 * <p>Runtimes may use these hooks to coordinate sink commits with checkpoints, for example to implement
 * best-effort end-to-end checkpointing.</p>
 *
 * <p>All methods are optional (default no-op) to preserve source/binary compatibility.</p>
 */
public interface CheckpointAwareSink<T> extends StreamSink<T> {

    /**
     * Called when a checkpoint is starting (typically after the runtime has paused sources).
     */
    default void onCheckpointStart(long checkpointId) throws Exception {
    }

    /**
     * Called after a checkpoint is successfully stored (checkpoint completed).
     */
    default void onCheckpointComplete(long checkpointId) throws Exception {
    }

    /**
     * Called when a checkpoint fails/aborts.
     */
    default void onCheckpointAbort(long checkpointId, Throwable cause) {
    }

    /**
     * Called after the runtime restored state from a checkpoint, before resuming consumption.
     */
    default void onCheckpointRestore(long checkpointId) throws Exception {
    }
}

