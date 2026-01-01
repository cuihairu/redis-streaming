package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;

import java.time.Duration;

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
     * Equivalent to {@link #cancel()}.
     */
    @Override
    default void close() {
        cancel();
    }
}
