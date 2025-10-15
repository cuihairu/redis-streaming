package io.github.cuihairu.redis.streaming.mq.retry;

/**
 * Simple exponential backoff with cap. Jitter can be added by caller if needed.
 */
public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final int maxAttempts;
    private final long baseMs;
    private final long maxBackoffMs;

    public ExponentialBackoffRetryPolicy(int maxAttempts, long baseMs, long maxBackoffMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseMs = Math.max(0, baseMs);
        this.maxBackoffMs = Math.max(baseMs, maxBackoffMs);
    }

    @Override
    public int getMaxAttempts() {
        return maxAttempts;
    }

    @Override
    public long nextBackoffMs(int attempt) {
        long v = baseMs * (1L << Math.max(0, attempt - 1));
        return Math.min(v, maxBackoffMs);
    }
}

