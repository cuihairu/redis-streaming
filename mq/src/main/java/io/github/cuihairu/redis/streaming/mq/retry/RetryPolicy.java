package io.github.cuihairu.redis.streaming.mq.retry;

/**
 * Retry policy abstraction. Used to determine max attempts and next backoff.
 * Note: initial implementation may ignore delay and re-enqueue immediately.
 */
public interface RetryPolicy {
    int getMaxAttempts();

    /**
     * Compute next backoff in milliseconds for the given attempt (1-based).
     */
    long nextBackoffMs(int attempt);
}

