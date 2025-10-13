package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {

    private RetryExecutor executor;

    @BeforeEach
    void setUp() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(10))
                .exponentialBackoff(false)
                .build();
        executor = new RetryExecutor(policy);
    }

    @Test
    void testSuccessfulExecution() throws Exception {
        String result = executor.execute(input -> "success", "input");
        assertEquals("success", result);
    }

    @Test
    void testRetryUntilSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        String result = executor.execute(input -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Temporary failure");
            }
            return "success";
        }, "input");

        assertEquals("success", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void testMaxAttemptsExceeded() {
        AtomicInteger attempts = new AtomicInteger(0);

        assertThrows(RuntimeException.class, () ->
                executor.execute(input -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("Permanent failure");
                }, "input")
        );

        // Should try: initial + 3 retries = 4 total
        assertEquals(4, attempts.get());
    }

    @Test
    void testNoRetryOnNonRetryableException() {
        @SuppressWarnings("unchecked")
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .nonRetryableExceptions(new Class[]{IllegalStateException.class})
                .build();
        RetryExecutor executor = new RetryExecutor(policy);

        AtomicInteger attempts = new AtomicInteger(0);

        assertThrows(IllegalStateException.class, () ->
                executor.execute(input -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("Non-retryable");
                }, "input")
        );

        // Should only try once
        assertEquals(1, attempts.get());
    }

    @Test
    void testRetryDelay() throws Exception {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2)
                .initialDelay(Duration.ofMillis(50))
                .exponentialBackoff(false)
                .build();
        RetryExecutor executor = new RetryExecutor(policy);

        AtomicInteger attempts = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        executor.execute(input -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException("Retry");
            }
            return "success";
        }, "input");

        long duration = System.currentTimeMillis() - startTime;

        // Should have at least one 50ms delay
        assertTrue(duration >= 50);
    }

    @Test
    void testExecuteRunnable() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);

        executor.execute((RetryExecutor.RunnableWithException) counter::incrementAndGet);

        assertEquals(1, counter.get());
    }

    @Test
    void testRunnableWithRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        executor.execute((RetryExecutor.RunnableWithException) () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Retry");
            }
        });

        assertEquals(3, attempts.get());
    }

    @Test
    void testInterruptedRetry() {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(3)
                .initialDelay(Duration.ofSeconds(10)) // Long delay
                .build();
        RetryExecutor executor = new RetryExecutor(policy);

        Thread testThread = new Thread(() -> {
            try {
                executor.execute(input -> {
                    throw new RuntimeException("Fail");
                }, "input");
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("interrupted"));
            }
        });

        testThread.start();
        try {
            Thread.sleep(50); // Let it start
            testThread.interrupt();
            testThread.join(1000);
        } catch (InterruptedException e) {
            fail("Test interrupted");
        }
    }

    @Test
    void testGetPolicy() {
        RetryPolicy policy = executor.getPolicy();
        assertNotNull(policy);
        assertEquals(3, policy.getMaxAttempts());
    }
}
