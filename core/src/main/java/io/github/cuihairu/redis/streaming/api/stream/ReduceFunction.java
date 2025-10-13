package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;

/**
 * ReduceFunction combines two elements into one.
 *
 * @param <T> The type of elements
 */
@FunctionalInterface
public interface ReduceFunction<T> extends Serializable {

    /**
     * Reduce two elements into one
     *
     * @param value1 The first value
     * @param value2 The second value
     * @return The reduced value
     * @throws Exception if reduction fails
     */
    T reduce(T value1, T value2) throws Exception;
}
