package io.github.cuihairu.redis.streaming.runtime;

/**
 * Convenience marker class for the streaming runtime module.
 *
 * <p>The {@code runtime} module provides a minimal, single-threaded in-memory execution engine
 * ({@link StreamExecutionEnvironment}). It is intended for unit tests and examples.</p>
 *
 * <p>For production use cases, prefer the dedicated Redis-backed modules (mq/state/aggregation/cep)
 * or a mature distributed runtime (e.g. Apache Flink) depending on requirements.</p>
 */
public class RuntimePlaceholder {

    /**
     * This class is not meant to be instantiated.
     */
    private RuntimePlaceholder() {
        throw new UnsupportedOperationException("This class is not meant to be instantiated");
    }

    /**
     * Get information about the runtime module status.
     */
    public static String getStatus() {
        return "Runtime module provides a minimal in-memory runtime (StreamExecutionEnvironment). " +
                "It supports basic operators, keyed state, timers, event-time watermarks, and in-memory checkpointing. " +
                "It is intended for tests/examples (single-threaded, batch-style evaluation). " +
                "See runtime/README.md for details.";
    }
}
