package io.github.cuihairu.redis.streaming.runtime.redis;

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
     * Equivalent to {@link #cancel()}.
     */
    @Override
    default void close() {
        cancel();
    }
}

