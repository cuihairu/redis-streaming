package io.github.cuihairu.redis.streaming.mq.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the backoff shift overflow (MQ-07): {@code baseMs * (1L << (attempt-1))}
 * wrapped negative for large attempts, and {@code Math.min(negative, max)} then returned a
 * negative delay, turning retries into a zero-delay hot loop.
 */
class ExponentialBackoffRetryPolicyTest {

    @Test
    void normalExponentialGrowth() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(5, 1000, 60_000);
        assertEquals(1000, policy.nextBackoffMs(1));
        assertEquals(2000, policy.nextBackoffMs(2));
        assertEquals(4000, policy.nextBackoffMs(3));
        assertEquals(8000, policy.nextBackoffMs(4));
    }

    @Test
    void growthSaturatesAtMaxBackoff() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(5, 1000, 60_000);
        assertEquals(16_000, policy.nextBackoffMs(5));
        assertEquals(32_000, policy.nextBackoffMs(6));
        assertEquals(60_000, policy.nextBackoffMs(7));
        assertEquals(60_000, policy.nextBackoffMs(8));
    }

    @Test
    void veryLargeAttemptsSaturateInsteadOfOverflowing() {
        // Regression: 1L << 63 is negative and the product used to wrap for attempts >= ~54.
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(1000, 1000, 60_000);
        for (int attempt = 1; attempt <= 200; attempt++) {
            long backoff = policy.nextBackoffMs(attempt);
            assertTrue(backoff >= 0, "backoff must never be negative, got " + backoff + " at attempt " + attempt);
            assertTrue(backoff <= 60_000, "backoff must never exceed the cap, got " + backoff + " at attempt " + attempt);
        }
        assertEquals(60_000, policy.nextBackoffMs(64));
        assertEquals(60_000, policy.nextBackoffMs(100));
        assertEquals(60_000, policy.nextBackoffMs(200));
    }

    @Test
    void saturatesEvenWhenBaseWouldOverflowLong() {
        // base = 2^40 with cap 2^50: any attempt >= 2 must saturate, never wrap.
        ExponentialBackoffRetryPolicy policy =
                new ExponentialBackoffRetryPolicy(1000, 1L << 40, 1L << 50);
        assertEquals(1L << 40, policy.nextBackoffMs(1));
        assertEquals(1L << 41, policy.nextBackoffMs(2));
        assertEquals(1L << 50, policy.nextBackoffMs(11));
        assertEquals(1L << 50, policy.nextBackoffMs(70));
    }

    @Test
    void zeroBaseMeansImmediateRetry() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(5, 0, 60_000);
        assertEquals(0, policy.nextBackoffMs(1));
        assertEquals(0, policy.nextBackoffMs(50));
    }
}
