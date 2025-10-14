package io.github.cuihairu.redis.streaming.registry.client;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simple retry policy with exponential backoff and jitter.
 */
public class RetryPolicy {
    private final int maxAttempts;
    private final long initialDelayMs;
    private final double backoffFactor;
    private final long maxDelayMs;
    private final long jitterMs;

    public RetryPolicy(int maxAttempts, long initialDelayMs, double backoffFactor, long maxDelayMs, long jitterMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialDelayMs = Math.max(0, initialDelayMs);
        this.backoffFactor = Math.max(1.0, backoffFactor);
        this.maxDelayMs = Math.max(initialDelayMs, maxDelayMs);
        this.jitterMs = Math.max(0, jitterMs);
    }

    public int getMaxAttempts() { return maxAttempts; }

    public long computeDelayMs(int attempt) {
        double d = initialDelayMs * Math.pow(backoffFactor, Math.max(0, attempt - 1));
        long base = (long) Math.min(d, maxDelayMs);
        if (jitterMs <= 0) return base;
        long j = ThreadLocalRandom.current().nextLong(0, jitterMs + 1);
        return base + j;
    }
}

