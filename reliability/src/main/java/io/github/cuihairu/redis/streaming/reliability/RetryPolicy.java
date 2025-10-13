package io.github.cuihairu.redis.streaming.reliability;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Duration;

/**
 * Configuration for retry policies.
 */
@Data
@Builder
public class RetryPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Maximum number of retry attempts
     */
    @Builder.Default
    private final int maxAttempts = 3;

    /**
     * Initial delay between retries
     */
    @Builder.Default
    private final Duration initialDelay = Duration.ofMillis(100);

    /**
     * Maximum delay between retries
     */
    @Builder.Default
    private final Duration maxDelay = Duration.ofSeconds(10);

    /**
     * Backoff multiplier for exponential backoff
     */
    @Builder.Default
    private final double backoffMultiplier = 2.0;

    /**
     * Whether to use exponential backoff
     */
    @Builder.Default
    private final boolean exponentialBackoff = true;

    /**
     * Exceptions that should trigger a retry
     */
    private final Class<? extends Exception>[] retryableExceptions;

    /**
     * Exceptions that should NOT trigger a retry
     */
    private final Class<? extends Exception>[] nonRetryableExceptions;

    /**
     * Calculate the delay for a specific retry attempt
     *
     * @param attemptNumber The attempt number (1-based)
     * @return The delay in milliseconds
     */
    public long getDelayForAttempt(int attemptNumber) {
        if (attemptNumber <= 0) {
            return 0;
        }

        long delayMillis = initialDelay.toMillis();

        if (exponentialBackoff && attemptNumber > 1) {
            delayMillis = (long) (delayMillis * Math.pow(backoffMultiplier, attemptNumber - 1));
        }

        return Math.min(delayMillis, maxDelay.toMillis());
    }

    /**
     * Check if an exception is retryable
     *
     * @param exception The exception to check
     * @return true if the exception should trigger a retry
     */
    public boolean isRetryable(Exception exception) {
        // Check non-retryable exceptions first
        if (nonRetryableExceptions != null) {
            for (Class<? extends Exception> nonRetryable : nonRetryableExceptions) {
                if (nonRetryable.isInstance(exception)) {
                    return false;
                }
            }
        }

        // If retryable exceptions are specified, only retry those
        if (retryableExceptions != null && retryableExceptions.length > 0) {
            for (Class<? extends Exception> retryable : retryableExceptions) {
                if (retryable.isInstance(exception)) {
                    return true;
                }
            }
            return false;
        }

        // By default, retry all exceptions
        return true;
    }

    /**
     * Create a simple retry policy with default settings
     *
     * @return A retry policy with 3 attempts and exponential backoff
     */
    public static RetryPolicy defaultPolicy() {
        return RetryPolicy.builder().build();
    }

    /**
     * Create a no-retry policy
     *
     * @return A retry policy that never retries
     */
    public static RetryPolicy noRetry() {
        return RetryPolicy.builder()
                .maxAttempts(0)
                .build();
    }

    /**
     * Create a fixed delay retry policy
     *
     * @param maxAttempts Maximum retry attempts
     * @param delay Fixed delay between retries
     * @return A retry policy with fixed delay
     */
    public static RetryPolicy fixedDelay(int maxAttempts, Duration delay) {
        return RetryPolicy.builder()
                .maxAttempts(maxAttempts)
                .initialDelay(delay)
                .exponentialBackoff(false)
                .build();
    }
}
