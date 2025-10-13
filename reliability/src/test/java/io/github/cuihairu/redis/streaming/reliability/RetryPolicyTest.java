package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void testDefaultPolicy() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        assertEquals(3, policy.getMaxAttempts());
        assertTrue(policy.isExponentialBackoff());
        assertEquals(100, policy.getInitialDelay().toMillis());
    }

    @Test
    void testNoRetry() {
        RetryPolicy policy = RetryPolicy.noRetry();

        assertEquals(0, policy.getMaxAttempts());
    }

    @Test
    void testFixedDelay() {
        RetryPolicy policy = RetryPolicy.fixedDelay(5, Duration.ofSeconds(1));

        assertEquals(5, policy.getMaxAttempts());
        assertFalse(policy.isExponentialBackoff());
        assertEquals(1000, policy.getInitialDelay().toMillis());
    }

    @Test
    void testExponentialBackoff() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .initialDelay(Duration.ofMillis(100))
                .backoffMultiplier(2.0)
                .exponentialBackoff(true)
                .build();

        assertEquals(100, policy.getDelayForAttempt(1));
        assertEquals(200, policy.getDelayForAttempt(2));
        assertEquals(400, policy.getDelayForAttempt(3));
        assertEquals(800, policy.getDelayForAttempt(4));
    }

    @Test
    void testMaxDelay() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(10)
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(1))
                .backoffMultiplier(2.0)
                .exponentialBackoff(true)
                .build();

        // Should be capped at max delay
        assertTrue(policy.getDelayForAttempt(10) <= 1000);
        assertEquals(1000, policy.getDelayForAttempt(20));
    }

    @Test
    void testFixedDelayNoBackoff() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .initialDelay(Duration.ofMillis(500))
                .exponentialBackoff(false)
                .build();

        assertEquals(500, policy.getDelayForAttempt(1));
        assertEquals(500, policy.getDelayForAttempt(2));
        assertEquals(500, policy.getDelayForAttempt(5));
    }

    @Test
    void testIsRetryableDefault() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        // By default, all exceptions are retryable
        assertTrue(policy.isRetryable(new RuntimeException()));
        assertTrue(policy.isRetryable(new IllegalArgumentException()));
    }

    @Test
    void testIsRetryableWithWhitelist() {
        @SuppressWarnings("unchecked")
        RetryPolicy policy = RetryPolicy.builder()
                .retryableExceptions(new Class[]{IllegalArgumentException.class})
                .build();

        assertTrue(policy.isRetryable(new IllegalArgumentException()));
        assertFalse(policy.isRetryable(new RuntimeException()));
    }

    @Test
    void testIsRetryableWithBlacklist() {
        @SuppressWarnings("unchecked")
        RetryPolicy policy = RetryPolicy.builder()
                .nonRetryableExceptions(new Class[]{IllegalStateException.class})
                .build();

        assertFalse(policy.isRetryable(new IllegalStateException()));
        assertTrue(policy.isRetryable(new RuntimeException()));
    }

    @Test
    void testIsRetryableWithBothLists() {
        @SuppressWarnings("unchecked")
        RetryPolicy policy = RetryPolicy.builder()
                .retryableExceptions(new Class[]{RuntimeException.class})
                .nonRetryableExceptions(new Class[]{IllegalStateException.class})
                .build();

        // Non-retryable takes precedence
        assertFalse(policy.isRetryable(new IllegalStateException()));
        // IllegalArgumentException extends RuntimeException, so it should be retryable
        assertTrue(policy.isRetryable(new IllegalArgumentException()));
        // Plain RuntimeException should be retryable
        assertTrue(policy.isRetryable(new RuntimeException()));
    }

    @Test
    void testDelayForZeroAttempt() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();
        assertEquals(0, policy.getDelayForAttempt(0));
    }

    @Test
    void testDelayForNegativeAttempt() {
        RetryPolicy policy = RetryPolicy.defaultPolicy();
        assertEquals(0, policy.getDelayForAttempt(-1));
    }
}
