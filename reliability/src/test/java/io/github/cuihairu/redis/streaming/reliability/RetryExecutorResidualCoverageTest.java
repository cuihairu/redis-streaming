package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the residual {@link RetryExecutor#execute(Function, Object)} branches:
 * a zero-delay policy must retry without sleeping, and exceptions that are not
 * {@link RuntimeException}s must pass through {@code unwrap} untouched.
 */
class RetryExecutorResidualCoverageTest {

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    @Test
    void zeroDelayPolicyRetriesWithoutSleeping() throws Exception {
        RetryExecutor executor = new RetryExecutor(RetryPolicy.fixedDelay(3, Duration.ZERO));
        AtomicInteger attempts = new AtomicInteger();

        String out = executor.execute((String in) -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("flaky");
            }
            return in + "-ok";
        }, "x");

        assertEquals("x-ok", out);
        assertEquals(3, attempts.get(), "zero-delay policy must retry twice after the initial failure");
    }

    @Test
    void positiveDelayPolicyStillSleepsBetweenAttempts() throws Exception {
        RetryExecutor executor = new RetryExecutor(RetryPolicy.fixedDelay(2, Duration.ofMillis(5)));
        AtomicInteger attempts = new AtomicInteger();

        String out = executor.execute((String in) -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IllegalStateException("flaky");
            }
            return "done";
        }, "x");

        assertEquals("done", out);
        assertEquals(2, attempts.get());
    }

    @Test
    void nonRuntimeExceptionsAreNotUnwrapped() {
        RetryExecutor executor = new RetryExecutor(RetryPolicy.noRetry());
        IOException boom = new IOException("checked");

        Exception thrown = assertThrows(Exception.class, () ->
                executor.execute((Function<String, String>) in -> {
                    throw sneakyThrow(boom);
                }, "x"));

        assertSame(boom, thrown, "a non-RuntimeException must be rethrown as-is");
    }
}
