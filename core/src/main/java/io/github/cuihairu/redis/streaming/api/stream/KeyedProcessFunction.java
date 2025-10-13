package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;

/**
 * KeyedProcessFunction allows stateful processing of keyed streams.
 *
 * @param <K> The type of the key
 * @param <I> The type of input elements
 * @param <O> The type of output elements
 */
public interface KeyedProcessFunction<K, I, O> extends Serializable {

    /**
     * Process an element from the keyed stream
     *
     * @param key The key of the element
     * @param value The element value
     * @param ctx The context for accessing state and timers
     * @param out The collector for emitting results
     * @throws Exception if processing fails
     */
    void processElement(K key, I value, Context ctx, Collector<O> out) throws Exception;

    /**
     * Context for accessing state and timers
     */
    interface Context {
        /**
         * Get the current processing time
         */
        long currentProcessingTime();

        /**
         * Get the current watermark
         */
        long currentWatermark();

        /**
         * Register a processing time timer
         */
        void registerProcessingTimeTimer(long time);

        /**
         * Register an event time timer
         */
        void registerEventTimeTimer(long time);
    }

    /**
     * Collector for emitting output elements
     */
    interface Collector<T> {
        void collect(T value);
    }
}
