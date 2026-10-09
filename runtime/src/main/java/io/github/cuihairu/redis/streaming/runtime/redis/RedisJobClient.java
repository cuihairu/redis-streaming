package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;

import java.time.Duration;
import java.util.Map;

/**
 * Handle for a running Redis-backed streaming job.
 */
public interface RedisJobClient extends AutoCloseable {

    /**
     * Request job cancellation (best-effort).
     */
    void cancel();

    /**
     * Wait for the job to stop.
     *
     * @return true if the job stopped within timeout.
     */
    boolean awaitTermination(Duration timeout) throws InterruptedException;

    /**
     * Trigger a stop-the-world checkpoint immediately (best-effort).
     *
     * <p>Returns the created checkpoint when supported and successful; otherwise returns null.</p>
     */
    default Checkpoint triggerCheckpointNow() {
        return null;
    }

    /**
     * Read the latest checkpoint (best-effort).
     */
    default Checkpoint getLatestCheckpoint() {
        return null;
    }

    /**
     * Pause consumption (best-effort). Requires a pausable consumer implementation.
     */
    default void pause() {
    }

    /**
     * Resume consumption (best-effort). Requires a pausable consumer implementation.
     */
    default void resume() {
    }

    /**
     * Total in-flight message handling count (best-effort). Returns -1 when unsupported.
     */
    default long inFlight() {
        return -1L;
    }

    /**
     * Best-effort diagnostics snapshot for ops/troubleshooting.
     *
     * <p>Content is intentionally lightweight and may vary across runtime versions.</p>
     */
    default Map<String, Object> diagnostics() {
        return Map.of();
    }

    /**
     * Change the job's pipeline parallelism at runtime (dynamic scaling).
     *
     * <p>Subtasks are added (scale up) or stopped and removed (scale down) for every
     * pipeline of the job, and every live consumer is re-pinned to
     * {@code partitionId % newParallelism == subtaskIndex}. Partition handover rides the
     * MQ lease rebalance, so a brief handover window delivers at-least-once. Keyed state
     * and checkpoints are partition-keyed (never subtask-keyed), so they remain valid
     * across the change — including restoring a checkpoint written at a different
     * parallelism.</p>
     *
     * <p>The resize is exclusive with checkpoint flows: it waits (bounded) for an
     * in-flight checkpoint to finish before mutating the subtask lists.</p>
     *
     * @return true when applied; false when unsupported by this job client, the job is
     *         canceled, the resize failed midway, or the checkpoint gate could not be
     *         acquired in time.
     * @throws IllegalArgumentException when newParallelism &lt; 1
     */
    default boolean scaleParallelism(int newParallelism) {
        return false;
    }

    /**
     * Equivalent to {@link #cancel()}.
     */
    @Override
    default void close() {
        cancel();
    }
}
