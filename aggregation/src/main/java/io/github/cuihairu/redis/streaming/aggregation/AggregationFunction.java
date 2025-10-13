package io.github.cuihairu.redis.streaming.aggregation;

import java.util.Collection;

/**
 * Aggregation function interface for combining values
 *
 * @param <T> input type
 * @param <R> result type
 */
@FunctionalInterface
public interface AggregationFunction<T, R> {

    /**
     * Apply aggregation function to a collection of values
     *
     * @param values the values to aggregate
     * @return aggregation result
     */
    R apply(Collection<T> values);

    /**
     * Get the name of this aggregation function
     *
     * @return function name
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}