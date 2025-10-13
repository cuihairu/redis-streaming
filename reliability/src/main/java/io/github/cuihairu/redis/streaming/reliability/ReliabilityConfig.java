package io.github.cuihairu.redis.streaming.reliability;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.function.Function;

/**
 * Configuration for reliable stream processing with failure handling.
 *
 * @param <T> The type of stream elements
 */
@Data
@Builder
public class ReliabilityConfig<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The failure strategy to use
     */
    @Builder.Default
    private final FailureStrategy failureStrategy = FailureStrategy.RETRY;

    /**
     * Retry policy for RETRY strategy
     */
    @Builder.Default
    private final RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();

    /**
     * Dead letter queue for DEAD_LETTER_QUEUE strategy
     */
    private final DeadLetterQueue<T> deadLetterQueue;

    /**
     * Function to execute on failure (for logging, metrics, etc.)
     */
    private final Function<FailedElement<T>, Void> onFailureCallback;

    /**
     * Whether to continue processing after a failure
     */
    @Builder.Default
    private final boolean continueOnFailure = true;

    /**
     * Maximum number of consecutive failures before stopping
     */
    @Builder.Default
    private final int maxConsecutiveFailures = 10;

    /**
     * Validate the configuration
     */
    public void validate() {
        if (failureStrategy == null) {
            throw new IllegalArgumentException("Failure strategy must be specified");
        }

        if (failureStrategy == FailureStrategy.RETRY && retryPolicy == null) {
            throw new IllegalArgumentException("Retry policy must be specified for RETRY strategy");
        }

        if (failureStrategy == FailureStrategy.DEAD_LETTER_QUEUE && deadLetterQueue == null) {
            throw new IllegalArgumentException("Dead letter queue must be specified for DEAD_LETTER_QUEUE strategy");
        }

        if (maxConsecutiveFailures <= 0) {
            throw new IllegalArgumentException("Max consecutive failures must be positive");
        }
    }

    /**
     * Create a simple retry configuration
     *
     * @param <T> The element type
     * @return A reliability config with retry strategy
     */
    public static <T> ReliabilityConfig<T> withRetry() {
        return ReliabilityConfig.<T>builder()
                .failureStrategy(FailureStrategy.RETRY)
                .retryPolicy(RetryPolicy.defaultPolicy())
                .build();
    }

    /**
     * Create a dead letter queue configuration
     *
     * @param queueSize Size of the dead letter queue
     * @param <T> The element type
     * @return A reliability config with DLQ strategy
     */
    public static <T> ReliabilityConfig<T> withDeadLetterQueue(int queueSize) {
        return ReliabilityConfig.<T>builder()
                .failureStrategy(FailureStrategy.DEAD_LETTER_QUEUE)
                .deadLetterQueue(new DeadLetterQueue<>(queueSize))
                .build();
    }

    /**
     * Create a fail-fast configuration
     *
     * @param <T> The element type
     * @return A reliability config with fail-fast strategy
     */
    public static <T> ReliabilityConfig<T> failFast() {
        return ReliabilityConfig.<T>builder()
                .failureStrategy(FailureStrategy.FAIL_FAST)
                .continueOnFailure(false)
                .build();
    }
}
