package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extra tests covering RunnableWithException cause unwrapping behavior.
 */
class RetryExecutorRunnableTest {

    @Test
    void nonRetryableCauseStopsImmediately() {
        @SuppressWarnings("unchecked")
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .nonRetryableExceptions(new Class[]{IllegalStateException.class})
                .initialDelay(Duration.ofMillis(1))
                .build();
        RetryExecutor executor = new RetryExecutor(policy);

        AtomicInteger attempts = new AtomicInteger(0);
        Exception ex = assertThrows(IllegalStateException.class, () ->
                executor.execute((RetryExecutor.RunnableWithException) () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("boom");
                })
        );
        assertEquals("boom", ex.getMessage());
        assertEquals(1, attempts.get(), "should not retry non-retryable cause");
    }

    @Test
    void retryableCheckedExceptionIsRetriedAndUnwrappedOnGiveUp() {
        @SuppressWarnings("unchecked")
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2)
                .initialDelay(Duration.ofMillis(1))
                .retryableExceptions(new Class[]{java.io.IOException.class})
                .build();
        RetryExecutor executor = new RetryExecutor(policy);

        AtomicInteger attempts = new AtomicInteger(0);
        Exception ex = assertThrows(java.io.IOException.class, () ->
                executor.execute((RetryExecutor.RunnableWithException) () -> {
                    attempts.incrementAndGet();
                    throw new java.io.IOException("iofail");
                })
        );
        // initial + 2 retries = 3 attempts
        assertEquals(3, attempts.get());
        assertEquals("iofail", ex.getMessage());
    }
}

