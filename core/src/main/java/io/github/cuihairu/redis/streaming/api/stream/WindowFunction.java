package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;

/**
 * WindowFunction processes all elements in a window.
 *
 * @param <K> The type of the key
 * @param <IN> The type of input elements
 * @param <OUT> The type of output elements
 */
@FunctionalInterface
public interface WindowFunction<K, IN, OUT> extends Serializable {

    /**
     * Process all elements in a window
     *
     * @param key The key of the window
     * @param window The window
     * @param elements All elements in the window
     * @param out The collector for emitting results
     * @throws Exception if processing fails
     */
    void apply(K key, WindowAssigner.Window window, Iterable<IN> elements, Collector<OUT> out) throws Exception;

    /**
     * Collector for emitting output elements
     */
    interface Collector<T> {
        void collect(T value);
    }
}
