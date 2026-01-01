package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RetryPolicy
 */
class RetryPolicyTest {

    @Test
    void testBuilderWithDefaults() {
        // Given & When
        RetryPolicy policy = RetryPolicy.builder().build();

        // Then
        assertEquals(3, policy.getMaxAttempts());
        assertEquals(Duration.ofMillis(100), policy.getInitialDelay());
        assertEquals(Duration.ofSeconds(10), policy.getMaxDelay());
        assertEquals(2.0, policy.getBackoffMultiplier());
        assertTrue(policy.isExponentialBackoff());
    }

    @Test
    void testBuilderWithCustomValues() {
        // Given
        int maxAttempts = 5;
        Duration initialDelay = Duration.ofMillis(200);
        Duration maxDelay = Duration.ofSeconds(30);
        double backoffMultiplier = 3.0;
        boolean exponentialBackoff = false;

        // When
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .initialDelay(initialDelay)
                .maxDelay(maxDelay)
                .backoffMultiplier(backoffMultiplier)
                .exponentialBackoff(exponentialBackoff)
                .build();

        // Then
        assertEquals(maxAttempts, policy.getMaxAttempts());
        assertEquals(initialDelay, policy.getInitialDelay());
        assertEquals(maxDelay, policy.getMaxDelay());
        assertEquals(backoffMultiplier, policy.getBackoffMultiplier());
        assertEquals(exponentialBackoff, policy.isExponentialBackoff());
    }

    @Test
    void testGetDelayForAttemptWithZero() {
        // Given
        RetryPolicy policy = RetryPolicy.builder().build();

        // When
        long delay = policy.getDelayForAttempt(0);

        // Then
        assertEquals(0, delay);
    }

    @Test
    void testGetDelayForAttemptWithNegative() {
        // Given
        RetryPolicy policy = RetryPolicy.builder().build();

        // When
        long delay = policy.getDelayForAttempt(-1);

        // Then
        assertEquals(0, delay);
    }

    @Test
    void testGetDelayForAttemptFirstRetry() {
        // Given
        RetryPolicy policy = RetryPolicy.builder()
                .initialDelay(Duration.ofMillis(100))
                .build();

        // When
        long delay = policy.getDelayForAttempt(1);

        // Then
        assertEquals(100, delay);
    }

    @Test
    void testGetDelayForAttemptWithExponentialBackoff() {
        // Given
        RetryPolicy policy = RetryPolicy.builder()
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(10))
                .backoffMultiplier(2.0)
                .exponentialBackoff(true)
                .build();

        // When & Then
        assertEquals(100, policy.getDelayForAttempt(1));  // 100 * 2^0 = 100
        assertEquals(200, policy.getDelayForAttempt(2));  // 100 * 2^1 = 200
        assertEquals(400, policy.getDelayForAttempt(3));  // 100 * 2^2 = 400
        assertEquals(800, policy.getDelayForAttempt(4));  // 100 * 2^3 = 800
        assertEquals(1600, policy.getDelayForAttempt(5)); // 100 * 2^4 = 1600
    }

    @Test
    void testGetDelayForAttemptCappedAtMaxDelay() {
        // Given
        RetryPolicy policy = RetryPolicy.builder()
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofMillis(500))
                .backoffMultiplier(3.0)
                .exponentialBackoff(true)
                .build();

        // When & Then
        assertEquals(100, policy.getDelayForAttempt(1));  // 100
        assertEquals(300, policy.getDelayForAttempt(2));  // 100 * 3^1 = 300
        assertEquals(500, policy.getDelayForAttempt(3));  // 100 * 3^2 = 900, capped at 500
        assertEquals(500, policy.getDelayForAttempt(4));  // 100 * 3^3 = 2700, capped at 500
    }

    @Test
    void testGetDelayForAttemptWithoutExponentialBackoff() {
        // Given
        RetryPolicy policy = RetryPolicy.builder()
                .initialDelay(Duration.ofMillis(200))
                .exponentialBackoff(false)
                .build();

        // When & Then
        assertEquals(200, policy.getDelayForAttempt(1));
        assertEquals(200, policy.getDelayForAttempt(2));
        assertEquals(200, policy.getDelayForAttempt(3));
        assertEquals(200, policy.getDelayForAttempt(10));
    }

    @Test
    void testIsRetryableWithNoFilters() {
        // Given
        RetryPolicy policy = RetryPolicy.builder().build();
        IOException exception = new IOException("Test error");

        // When
        boolean retryable = policy.isRetryable(exception);

        // Then - by default, all exceptions are retryable
        assertTrue(retryable);
    }

    @Test
    void testIsRetryableWithRetryableExceptions() {
        // Given
        RetryPolicy policy = RetryPolicy.builder()
                .retryableExceptions(new Class[]{IOException.class, SocketException.class})
                .build();

        // When & Then
        assertTrue(policy.isRetryable(new IOException()));
        assertTrue(policy.isRetryable(new SocketException()));
        assertFalse(policy.isRetryable(new RuntimeException()));
        assertFalse(policy.isRetryable(new IllegalArgumentException()));
    }

    @Test
    void testIsRetryableWithNonRetryableExceptions() {
        // Given
        RetryPolicy policy = RetryPolicy.builder()
                .nonRetryableExceptions(new Class[]{IllegalArgumentException.class, NullPointerException.class})
                .build();

        // When & Then
        assertFalse(policy.isRetryable(new IllegalArgumentException()));
        assertFalse(policy.isRetryable(new NullPointerException()));
        assertTrue(policy.isRetryable(new IOException()));
        assertTrue(policy.isRetryable(new RuntimeException()));
    }

    @Test
    void testIsRetryableWithBothFilters() {
        // Given - non-retryable takes precedence
        RetryPolicy policy = RetryPolicy.builder()
                .retryableExceptions(new Class[]{IOException.class, RuntimeException.class})
                .nonRetryableExceptions(new Class[]{IllegalArgumentException.class})
                .build();

        // When & Then
        assertFalse(policy.isRetryable(new IllegalArgumentException())); // non-retryable wins
        assertTrue(policy.isRetryable(new IOException()));
        assertTrue(policy.isRetryable(new RuntimeException()));
        assertFalse(policy.isRetryable(new Exception()));
    }

    @Test
    void testIsRetryableWithInheritance() {
        // Given
        RetryPolicy policy = RetryPolicy.builder()
                .retryableExceptions(new Class[]{IOException.class})
                .build();

        // When & Then
        assertTrue(policy.isRetryable(new IOException()));
        assertTrue(policy.isRetryable(new SocketException())); // SocketException extends IOException
        assertTrue(policy.isRetryable(new SocketTimeoutException())); // extends SocketException
    }

    @Test
    void testDefaultPolicy() {
        // Given & When
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        // Then
        assertNotNull(policy);
        assertEquals(3, policy.getMaxAttempts());
        assertTrue(policy.isExponentialBackoff());
    }

    @Test
    void testNoRetry() {
        // Given & When
        RetryPolicy policy = RetryPolicy.noRetry();

        // Then
        assertEquals(0, policy.getMaxAttempts());
    }

    @Test
    void testFixedDelay() {
        // Given
        int maxAttempts = 5;
        Duration delay = Duration.ofMillis(500);

        // When
        RetryPolicy policy = RetryPolicy.fixedDelay(maxAttempts, delay);

        // Then
        assertEquals(maxAttempts, policy.getMaxAttempts());
        assertEquals(delay, policy.getInitialDelay());
        assertFalse(policy.isExponentialBackoff());
        assertEquals(delay.toMillis(), policy.getDelayForAttempt(1));
        assertEquals(delay.toMillis(), policy.getDelayForAttempt(2));
        assertEquals(delay.toMillis(), policy.getDelayForAttempt(3));
    }

    @Test
    void testIsSerializable() {
        // Given
        RetryPolicy policy = RetryPolicy.builder().build();

        // When & Then
        assertTrue(policy instanceof java.io.Serializable);
    }

    @Test
    void testGettersAndSetters() {
        // Given
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(10)
                .initialDelay(Duration.ofMillis(50))
                .maxDelay(Duration.ofSeconds(5))
                .backoffMultiplier(1.5)
                .exponentialBackoff(true)
                .build();

        // When & Then
        assertEquals(10, policy.getMaxAttempts());
        assertEquals(Duration.ofMillis(50), policy.getInitialDelay());
        assertEquals(Duration.ofSeconds(5), policy.getMaxDelay());
        assertEquals(1.5, policy.getBackoffMultiplier(), 0.001);
        assertTrue(policy.isExponentialBackoff());
    }

    @Test
    void testEmptyRetryableExceptionsMeansRetryAll() {
        // Given
        RetryPolicy policy = RetryPolicy.builder()
                .retryableExceptions(new Class[]{})
                .build();

        // When
        boolean result1 = policy.isRetryable(new IOException());
        boolean result2 = policy.isRetryable(new RuntimeException());

        // Then - empty array means retry all
        assertTrue(result1);
        assertTrue(result2);
    }
}
