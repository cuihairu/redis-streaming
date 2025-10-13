package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;

/**
 * AggregateFunction defines how to aggregate elements in a window.
 *
 * @param <IN> The type of input elements
 * @param <OUT> The type of the result
 */
public interface AggregateFunction<IN, OUT> extends Serializable {

    /**
     * Create a new accumulator
     */
    Accumulator<IN> createAccumulator();

    /**
     * Add an element to the accumulator
     */
    Accumulator<IN> add(IN value, Accumulator<IN> accumulator);

    /**
     * Get the result from the accumulator
     */
    OUT getResult(Accumulator<IN> accumulator);

    /**
     * Merge two accumulators
     */
    Accumulator<IN> merge(Accumulator<IN> a, Accumulator<IN> b);

    /**
     * Accumulator holds intermediate aggregation state
     */
    interface Accumulator<T> extends Serializable {
    }
}
