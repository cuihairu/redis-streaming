package io.github.cuihairu.redis.streaming.aggregation;

import java.util.Collection;

/**
 * Aggregation function interface for combining values
 *
 * @param <T> input type
 * @param <R> result type
 * @deprecated batch interface: apply-at-once, no incremental accumulation, no merge
 *     across partitions. Windowed pipelines must implement
 *     {@link io.github.cuihairu.redis.streaming.api.stream.AggregateFunction} instead
 *     (ready-made incremental implementations live in
 *     {@code io.github.cuihairu.redis.streaming.aggregation.incremental}); for one-shot
 *     batch computation, reduce the collection directly with {@code Stream.collect}.
 *     Slated for removal after the deprecation period.
 */
@Deprecated
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