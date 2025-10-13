package io.github.cuihairu.redis.streaming.runtime;

/**
 * Placeholder class for the streaming runtime module.
 *
 * The runtime module is currently in development. See README.md for details.
 *
 * For now, please use the individual modules directly:
 * - MQ for message queuing
 * - State for state management
 * - Aggregation for windowed operations
 * - CEP for complex event processing
 */
public class RuntimePlaceholder {

    /**
     * This class is not meant to be instantiated.
     */
    private RuntimePlaceholder() {
        throw new UnsupportedOperationException("This is a placeholder class");
    }

    /**
     * Get information about the runtime module status.
     */
    public static String getStatus() {
        return "Runtime module is in development. " +
               "Please use individual modules (mq, state, aggregation, cep) directly. " +
               "See runtime/README.md for details.";
    }
}
