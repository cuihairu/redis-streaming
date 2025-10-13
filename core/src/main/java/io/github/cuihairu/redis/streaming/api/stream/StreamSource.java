package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;

/**
 * StreamSource is a source that produces elements for a stream.
 *
 * @param <T> The type of elements produced
 */
public interface StreamSource<T> extends Serializable {

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
