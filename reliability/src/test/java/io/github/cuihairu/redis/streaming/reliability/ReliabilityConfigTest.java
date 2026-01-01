package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReliabilityConfig
 */
class ReliabilityConfigTest {

    @Test
    void testBuilderWithDefaults() {
        // Given & When
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder().build();

        // Then
        assertEquals(FailureStrategy.RETRY, config.getFailureStrategy());
        assertNotNull(config.getRetryPolicy());
        assertTrue(config.isContinueOnFailure());
        assertEquals(10, config.getMaxConsecutiveFailures());
    }

    @Test
    void testBuilderWithCustomValues() {
        // Given
        FailureStrategy strategy = FailureStrategy.FAIL_FAST;
        boolean continueOnFailure = false;
        int maxConsecutiveFailures = 5;

        // When
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(strategy)
                .continueOnFailure(continueOnFailure)
                .maxConsecutiveFailures(maxConsecutiveFailures)
                .build();

        // Then
        assertEquals(strategy, config.getFailureStrategy());
        assertEquals(continueOnFailure, config.isContinueOnFailure());
        assertEquals(maxConsecutiveFailures, config.getMaxConsecutiveFailures());
    }

    @Test
    void testValidateWithNullStrategy() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(null)
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                config::validate
        );
        assertTrue(exception.getMessage().contains("Failure strategy must be specified"));
    }

    @Test
    void testValidateWithRetryStrategyButNoPolicy() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(FailureStrategy.RETRY)
                .retryPolicy(null)
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                config::validate
        );
        assertTrue(exception.getMessage().contains("Retry policy must be specified"));
    }

    @Test
    void testValidateWithDeadLetterQueueStrategyButNoQueue() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(FailureStrategy.DEAD_LETTER_QUEUE)
                .deadLetterQueue(null)
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                config::validate
        );
        assertTrue(exception.getMessage().contains("Dead letter queue must be specified"));
    }

    @Test
    void testValidateWithInvalidMaxConsecutiveFailures() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .maxConsecutiveFailures(0)
                .build();

        // When & Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                config::validate
        );
        assertTrue(exception.getMessage().contains("Max consecutive failures must be positive"));
    }

    @Test
    void testValidateWithNegativeMaxConsecutiveFailures() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .maxConsecutiveFailures(-5)
                .build();

        // When & Then
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void testValidateSuccessWithRetry() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(FailureStrategy.RETRY)
                .retryPolicy(RetryPolicy.defaultPolicy())
                .build();

        // When & Then - should not throw
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testValidateSuccessWithDeadLetterQueue() {
        // Given
        DeadLetterQueue<String> dlq = new DeadLetterQueue<>(100);
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(FailureStrategy.DEAD_LETTER_QUEUE)
                .deadLetterQueue(dlq)
                .build();

        // When & Then - should not throw
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testValidateSuccessWithFailFast() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(FailureStrategy.FAIL_FAST)
                .build();

        // When & Then - should not throw
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testValidateSuccessWithSkip() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(FailureStrategy.SKIP)
                .build();

        // When & Then - should not throw
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testValidateSuccessWithIgnore() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(FailureStrategy.IGNORE)
                .build();

        // When & Then - should not throw
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testWithRetry() {
        // Given & When
        ReliabilityConfig<String> config = ReliabilityConfig.withRetry();

        // Then
        assertEquals(FailureStrategy.RETRY, config.getFailureStrategy());
        assertNotNull(config.getRetryPolicy());
    }

    @Test
    void testWithDeadLetterQueue() {
        // Given
        int queueSize = 100;

        // When
        ReliabilityConfig<String> config = ReliabilityConfig.withDeadLetterQueue(queueSize);

        // Then
        assertEquals(FailureStrategy.DEAD_LETTER_QUEUE, config.getFailureStrategy());
        assertNotNull(config.getDeadLetterQueue());
    }

    @Test
    void testFailFast() {
        // Given & When
        ReliabilityConfig<String> config = ReliabilityConfig.failFast();

        // Then
        assertEquals(FailureStrategy.FAIL_FAST, config.getFailureStrategy());
        assertFalse(config.isContinueOnFailure());
    }

    @Test
    void testWithOnFailureCallback() {
        // Given
        Function<FailedElement<String>, Void> callback = failed -> {
            System.out.println("Failed: " + failed.getErrorMessage());
            return null;
        };

        // When
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .onFailureCallback(callback)
                .build();

        // Then
        assertEquals(callback, config.getOnFailureCallback());
    }

    @Test
    void testWithAllFields() {
        // Given
        DeadLetterQueue<String> dlq = new DeadLetterQueue<>(100);
        Function<FailedElement<String>, Void> callback = failed -> null;
        RetryPolicy retryPolicy = RetryPolicy.fixedDelay(5, Duration.ofMillis(200));

        // When
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder()
                .failureStrategy(FailureStrategy.RETRY)
                .retryPolicy(retryPolicy)
                .deadLetterQueue(dlq)
                .onFailureCallback(callback)
                .continueOnFailure(true)
                .maxConsecutiveFailures(20)
                .build();

        // Then
        assertEquals(FailureStrategy.RETRY, config.getFailureStrategy());
        assertEquals(retryPolicy, config.getRetryPolicy());
        assertEquals(dlq, config.getDeadLetterQueue());
        assertEquals(callback, config.getOnFailureCallback());
        assertTrue(config.isContinueOnFailure());
        assertEquals(20, config.getMaxConsecutiveFailures());
    }

    @Test
    void testIsSerializable() {
        // Given
        ReliabilityConfig<String> config = ReliabilityConfig.<String>builder().build();

        // When & Then
        assertTrue(config instanceof java.io.Serializable);
    }

    @Test
    void testGenericTypes() {
        // Given & When
        ReliabilityConfig<String> stringConfig = ReliabilityConfig.<String>builder().build();
        ReliabilityConfig<Integer> intConfig = ReliabilityConfig.<Integer>builder().build();
        ReliabilityConfig<Object> objectConfig = ReliabilityConfig.<Object>builder().build();

        // Then - all should be valid
        assertNotNull(stringConfig);
        assertNotNull(intConfig);
        assertNotNull(objectConfig);
    }
}
