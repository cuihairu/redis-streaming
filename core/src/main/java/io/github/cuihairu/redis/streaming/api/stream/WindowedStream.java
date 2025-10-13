package io.github.cuihairu.redis.streaming.api.stream;

import java.util.function.Function;

/**
 * WindowedStream represents a stream with windowing applied.
 * It enables aggregations and computations over windows of data.
 *
 * @param <K> The type of the key
 * @param <T> The type of the elements
 */
public interface WindowedStream<K, T> {

    /**
     * Apply a reduce function to elements in each window
     *
     * @param reducer The reduce function
     * @return A DataStream with reduced window results
     */
    DataStream<T> reduce(ReduceFunction<T> reducer);

    /**
     * Apply an aggregate function to elements in each window
     *
     * @param aggregateFunction The aggregate function
     * @param <R> The type of the result
     * @return A DataStream with aggregated window results
     */
    <R> DataStream<R> aggregate(AggregateFunction<T, R> aggregateFunction);

    /**
     * Apply a window function to process all elements in a window
     *
     * @param windowFunction The window function
     * @param <R> The type of the result
     * @return A DataStream with window processing results
     */
    <R> DataStream<R> apply(WindowFunction<K, T, R> windowFunction);

    /**
     * Sum elements in each window by a field selector
     *
     * @param fieldSelector Function to extract the numeric field
     * @return A DataStream with summed window results
     */
    DataStream<T> sum(Function<T, ? extends Number> fieldSelector);

    /**
     * Count elements in each window
     *
     * @return A DataStream with count results
     */
    DataStream<Long> count();
}
