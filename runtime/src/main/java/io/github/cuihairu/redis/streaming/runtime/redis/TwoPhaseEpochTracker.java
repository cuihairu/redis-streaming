package io.github.cuihairu.redis.streaming.runtime.redis;

/**
 * Remembers which checkpoint currently holds the durable transaction handles of the
 * open two-phase-commit epoch. A checkpoint written but never sink-committed is adopted
 * as the restore point so recovery can finalize it — but once the epoch is discarded
 * (a later checkpoint fails before it is stored), that checkpoint must be marked as
 * aborted, otherwise recovery would adopt offsets for data that no longer exists.
 */
final class TwoPhaseEpochTracker {
    private final java.util.concurrent.atomic.AtomicLong durableCheckpointId =
            new java.util.concurrent.atomic.AtomicLong(-1L);

    /** The epoch's handles are now durable in this checkpoint. */
    void markDurable(long checkpointId) {
        durableCheckpointId.set(checkpointId);
    }

    /** The epoch was committed and sink-committed; its handles are consumed. */
    void clearDurable() {
        durableCheckpointId.set(-1L);
    }

    /** Returns the checkpoint holding the discarded epoch's handles, if any. */
    Long takeDurable() {
        long id = durableCheckpointId.getAndSet(-1L);
        return id < 0 ? null : id;
    }
}
