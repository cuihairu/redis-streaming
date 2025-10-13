package io.github.cuihairu.redis.streaming.api.stream;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;

import java.util.function.Function;

/**
 * KeyedStream represents a stream of elements partitioned by a key.
 * It enables stateful operations and windowing.
 *
 * @param <K> The type of the key
 * @param <T> The type of the elements
 */
public interface KeyedStream<K, T> {

    /**
     * Apply a map transformation to the keyed stream
     *
     * @param mapper The function to apply to each element
     * @param <R> The type of the resulting elements
     * @return A new KeyedStream with transformed elements
     */
    <K, R> KeyedStream<K, R> map(Function<T, R> mapper);

    /**
     * Apply a stateful process function to the stream
     *
     * @param processFunction The function to process elements with state
     * @param <R> The type of the resulting elements
     * @return A DataStream with processed elements
     */
    <R> DataStream<R> process(KeyedProcessFunction<K, T, R> processFunction);

    /**
     * Create a windowed stream with tumbling windows
     *
     * @param windowAssigner The window assigner to use
     * @return A WindowedStream
     */
    WindowedStream<K, T> window(WindowAssigner<T> windowAssigner);

    /**
     * Reduce the stream using a reduce function
     *
     * @param reducer The reduce function
     * @return A DataStream with reduced elements
     */
    DataStream<T> reduce(ReduceFunction<T> reducer);

    /**
     * Sum elements by a field selector
     *
     * @param fieldSelector Function to extract the numeric field
     * @return A DataStream with summed elements
     */
    DataStream<T> sum(Function<T, ? extends Number> fieldSelector);

    /**
     * Get or create a value state
     *
     * @param stateDescriptor The descriptor for the state
     * @param <S> The type of the state value
     * @return A ValueState instance
     */
    <S> ValueState<S> getState(StateDescriptor<S> stateDescriptor);
}
