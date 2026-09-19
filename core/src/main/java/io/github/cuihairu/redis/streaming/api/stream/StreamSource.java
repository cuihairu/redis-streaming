package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;

/**
 * StreamSource is a source that produces elements for a stream.
 *
 * @param <T> The type of elements produced
 */
public interface StreamSource<T> extends Serializable {

    /**
     * Called once by the runtime before {@link #run(SourceContext)} starts.
     *
     * <p>Use this hook to acquire resources such as connections or files. The default
     * implementation is a no-op, preserving compatibility with existing sources.</p>
     *
     * @throws Exception if the source cannot be initialized
     */
    default void open() throws Exception {
    }

    /**
     * Run the source to produce elements.
     * This method is called once when the source starts.
     *
     * @param ctx The source context for emitting elements
     * @throws Exception if the source fails
     */
    void run(SourceContext<T> ctx) throws Exception;

    /**
     * Cancel the source execution.
     * This method is called to stop the source gracefully.
     */
    default void cancel() {
        // Default: no-op
    }

    /**
     * Called once by the runtime after the source finished (or was cancelled).
     *
     * <p>Use this hook to release resources acquired in {@link #open()}. Implementations must
     * be idempotent; runtimes log but do not propagate exceptions thrown from this method.
     * The default implementation is a no-op.</p>
     *
     * @throws Exception if the resource release fails
     */
    default void close() throws Exception {
    }

    /**
     * SourceContext provides methods to emit elements from a source.
     *
     * @param <T> The type of elements
     */
    interface SourceContext<T> {
        /**
         * Emit an element to the stream
         *
         * @param element The element to emit
         */
        void collect(T element);

        /**
         * Emit an element with a timestamp
         *
         * @param element The element to emit
         * @param timestamp The timestamp of the element
         */
        void collectWithTimestamp(T element, long timestamp);

        /**
         * Get the checkpoint lock for thread-safe checkpointing
         */
        Object getCheckpointLock();

        /**
         * Check if the source should stop
         */
        boolean isStopped();
    }
}
