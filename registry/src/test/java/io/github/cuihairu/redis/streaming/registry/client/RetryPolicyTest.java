package io.github.cuihairu.redis.streaming.registry.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RetryPolicy
 */
class RetryPolicyTest {

    @Test
    void testConstructorWithDefaults() {
        RetryPolicy policy = new RetryPolicy(3, 100, 2.0, 5000, 50);
        assertEquals(3, policy.getMaxAttempts());
    }

    @Test
    void testConstructorWithMinAttempts() {
        RetryPolicy policy = new RetryPolicy(0, 100, 2.0, 5000, 50);
        assertEquals(1, policy.getMaxAttempts(), "maxAttempts should be at least 1");
    }

    @Test
    void testConstructorWithNegativeMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(-5, 100, 2.0, 5000, 50);
        assertEquals(1, policy.getMaxAttempts(), "negative maxAttempts should be normalized to 1");
    }

    @Test
    void testComputeDelayFirstAttempt() {
        RetryPolicy policy = new RetryPolicy(3, 100, 2.0, 5000, 0);
        long delay = policy.computeDelayMs(1);
        assertEquals(100, delay, "First attempt should use initial delay");
    }

    @Test
    void testComputeDelayWithBackoff() {
        RetryPolicy policy = new RetryPolicy(3, 100, 2.0, 5000, 0);
        assertEquals(100, policy.computeDelayMs(1));
        assertEquals(200, policy.computeDelayMs(2));
        assertEquals(400, policy.computeDelayMs(3));
        assertEquals(800, policy.computeDelayMs(4));
    }

    @Test
    void testComputeDelayWithMaxDelay() {
        RetryPolicy policy = new RetryPolicy(3, 100, 10.0, 500, 0);
        assertEquals(100, policy.computeDelayMs(1));
        assertEquals(500, policy.computeDelayMs(2)); // Would be 1000 but capped
        assertEquals(500, policy.computeDelayMs(3));
    }

    @Test
    void testComputeDelayWithZeroBackoffFactor() {
        RetryPolicy policy = new RetryPolicy(3, 100, 1.0, 5000, 0);
        assertEquals(100, policy.computeDelayMs(1));
        assertEquals(100, policy.computeDelayMs(2));
        assertEquals(100, policy.computeDelayMs(3));
    }

    @Test
    void testComputeDelayWithJitter() {
        RetryPolicy policy = new RetryPolicy(3, 100, 2.0, 5000, 50);
        long delay = policy.computeDelayMs(1);
        assertTrue(delay >= 100 && delay <= 150, "Delay should be base + jitter");
    }

    @Test
    void testComputeDelayWithLargerJitter() {
        RetryPolicy policy = new RetryPolicy(3, 100, 2.0, 5000, 200);
        long delay = policy.computeDelayMs(1);
        assertTrue(delay >= 100 && delay <= 300, "Delay should be base + jitter");
    }

    @Test
    void testComputeDelayZeroAttempt() {
        RetryPolicy policy = new RetryPolicy(3, 100, 2.0, 5000, 0);
        long delay = policy.computeDelayMs(0);
        assertEquals(100, delay, "Zero attempt should be treated as first");
    }

    @Test
    void testComputeDelayNegativeAttempt() {
        RetryPolicy policy = new RetryPolicy(3, 100, 2.0, 5000, 0);
        long delay = policy.computeDelayMs(-1);
        assertEquals(100, delay, "Negative attempt should be treated as first");
    }

    @Test
    void testComputeDelayLargeAttempt() {
        RetryPolicy policy = new RetryPolicy(3, 100, 2.0, 5000, 0);
        assertEquals(3200, policy.computeDelayMs(6));
        assertEquals(5000, policy.computeDelayMs(7));  // Capped at maxDelay
        assertEquals(5000, policy.computeDelayMs(8));  // Capped at maxDelay
        assertEquals(5000, policy.computeDelayMs(9));  // Capped at maxDelay
    }

    @Test
    void testGetMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(5, 100, 2.0, 5000, 50);
        assertEquals(5, policy.getMaxAttempts());
    }

    @Test
    void testComputeDelayConsistentForSameAttempt() {
        RetryPolicy policy = new RetryPolicy(3, 100, 2.0, 5000, 0);
        // Without jitter, delays should be consistent
        for (int i = 1; i <= 10; i++) {
            long delay1 = policy.computeDelayMs(i);
            long delay2 = policy.computeDelayMs(i);
            assertEquals(delay1, delay2, "Delay should be consistent for attempt " + i);
        }
    }
}
