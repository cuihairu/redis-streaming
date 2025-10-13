package io.github.cuihairu.redis.streaming.reliability;

import java.io.Serializable;
import java.util.function.Function;

/**
 * Executor that applies retry logic to functions.
 */
public class RetryExecutor implements Serializable {

    private static final long serialVersionUID = 1L;

    private final RetryPolicy policy;

    public RetryExecutor(RetryPolicy policy) {
        this.policy = policy;
    }

    /**
     * Execute a function with retry logic
     *
     * @param function The function to execute
     * @param input The input to the function
     * @param <T> The input type
     * @param <R> The result type
     * @return The result of the function
     * @throws Exception if all retry attempts fail
     */
    public <T, R> R execute(Function<T, R> function, T input) throws Exception {
        Exception lastException = null;
        int maxAttempts = Math.max(1, policy.getMaxAttempts() + 1); // +1 for initial attempt

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return function.apply(input);
            } catch (Exception e) {
                lastException = e;

                // Check if we should retry
                if (attempt >= maxAttempts || !policy.isRetryable(e)) {
                    throw e;
                }

                // Wait before retrying
                long delay = policy.getDelayForAttempt(attempt);
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                }
            }
        }

        // Should never reach here, but just in case
        throw lastException != null ? lastException : new RuntimeException("Retry failed");
    }

    /**
     * Execute a runnable with retry logic
     *
     * @param runnable The runnable to execute
     * @throws Exception if all retry attempts fail
     */
    public void execute(RunnableWithException runnable) throws Exception {
        execute(input -> {
            try {
                runnable.run();
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, null);
    }

    /**
     * Get the retry policy
     *
     * @return The retry policy
     */
    public RetryPolicy getPolicy() {
        return policy;
    }

    /**
     * Functional interface for runnables that can throw exceptions
     */
    @FunctionalInterface
    public interface RunnableWithException {
        void run() throws Exception;
    }
}
