package io.github.cuihairu.redis.streaming.mq.retry;

/**
 * Delivery retry policy abstraction for the MQ layer (renamed from {@code RetryPolicy} to
 * disambiguate from {@code reliability.RetryPolicy}, the user/operator-layer retry config POJO —
 * see Dedup-Retry-DLQ-Boundary-Design.md). Used to determine max attempts and next backoff.
 * Note: initial implementation may ignore delay and re-enqueue immediately.
 */
public interface DeliveryRetryPolicy {
    int getMaxAttempts();

    /**
     * Compute next backoff in milliseconds for the given attempt (1-based).
     */
    long nextBackoffMs(int attempt);
}

