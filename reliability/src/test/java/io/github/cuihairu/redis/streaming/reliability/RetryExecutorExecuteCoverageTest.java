package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers {@code RetryExecutor#execute(Function, Object)} success/retry/exhaust/unwrap paths. */
class RetryExecutorExecuteCoverageTest {

    @Test
    void returnsOnFirstSuccessfulAttempt() throws Exception {
        RetryExecutor executor = new RetryExecutor(RetryPolicy.fixedDelay(3, java.time.Duration.ofMillis(1)));
        String out = executor.execute((String in) -> in + "!", "hi");
        assertEquals("hi!", out);
    }

    @Test
    void retriesUntilSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        RetryExecutor executor = new RetryExecutor(RetryPolicy.fixedDelay(5, java.time.Duration.ofMillis(1)));
        String out = executor.execute((String in) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("transient");
            }
            return in + "-ok";
        }, "v");
        assertEquals("v-ok", out);
        assertEquals(3, attempts.get());
    }

    @Test
    void throwsWhenAttemptsExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        RetryExecutor executor = new RetryExecutor(RetryPolicy.fixedDelay(2, java.time.Duration.ofMillis(1)));
        Exception ex = assertThrows(Exception.class, () -> executor.execute((String in) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("always");
        }, "v"));
        assertEquals("always", ex.getMessage());
        assertEquals(3, attempts.get(), "initial attempt + maxAttempts retries");
    }

    @Test
    void throwsImmediatelyForNonRetryableException() {
        AtomicInteger attempts = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .initialDelay(java.time.Duration.ofMillis(1))
                .exponentialBackoff(false)
                .nonRetryableExceptions(new Class[]{IllegalStateException.class})
                .build();
        RetryExecutor executor = new RetryExecutor(policy);
        assertThrows(IllegalStateException.class, () -> executor.execute((String in) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("fatal");
        }, "v"));
        assertEquals(1, attempts.get());
    }

    @Test
    void unwrapsWrappedCheckedCauseBeforeRetryMatching() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .initialDelay(java.time.Duration.ofMillis(1))
                .exponentialBackoff(false)
                .retryableExceptions(new Class[]{IOException.class})
                .build();
        RetryExecutor executor = new RetryExecutor(policy);
        String out = executor.execute((String in) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException(new IOException("root"));
            }
            return "done";
        }, "v");
        assertEquals("done", out);
        assertEquals(2, attempts.get(), "wrapped IOException must be unwrapped and matched as retryable");
    }

    @Test
    void runnableOverloadDelegatesToFunctionExecutor() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        RetryExecutor executor = new RetryExecutor(RetryPolicy.fixedDelay(1, java.time.Duration.ofMillis(1)));
        executor.execute(() -> runs.incrementAndGet());
        assertEquals(1, runs.get());
    }

    @Test
    void interruptDuringBackoffThrowsWrappedException() {
        RetryExecutor executor = new RetryExecutor(RetryPolicy.fixedDelay(3, java.time.Duration.ofMillis(500)));
        Thread.currentThread().interrupt();
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, () -> executor.execute((Function<String, String>) in -> {
                throw new IllegalStateException("retry me");
            }, "v"));
            assertTrue(ex.getMessage().contains("Retry interrupted"));
        } finally {
            assertTrue(Thread.interrupted(), "interrupt flag preserved then cleared");
        }
    }
}
