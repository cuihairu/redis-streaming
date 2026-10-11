package io.github.cuihairu.redis.streaming.aggregation.incremental;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;

import java.util.Objects;

/**
 * Page-view (PV) counting as an incremental {@link AggregateFunction}: the classic PV
 * aggregate is a record counter, so this is the core-interface counterpart of
 * {@code analytics.PVCounter} without the Redis-backed trailing window.
 *
 * <p>Use this to count per-window records inside a pipeline
 * ({@code window(...).aggregate(new PVAggregateFunction<>())}); use
 * {@code PVCounter} when counts must survive restarts and be queryable outside the
 * pipeline. Null elements are rejected.
 *
 * @param <T> the input element type (counted, never inspected)
 */
public class PVAggregateFunction<T> implements AggregateFunction<T, Long> {

    private static final long serialVersionUID = 1L;

    @Override
    public CountAccumulator<T> createAccumulator() {
        return new CountAccumulator<>();
    }

    @Override
    public CountAccumulator<T> add(T value, AggregateFunction.Accumulator<T> accumulator) {
        Objects.requireNonNull(value, "value");
        CountAccumulator<T> acc = requireAccumulator(accumulator);
        acc.count++;
        return acc;
    }

    @Override
    public Long getResult(AggregateFunction.Accumulator<T> accumulator) {
        return requireAccumulator(accumulator).count;
    }

    @Override
    public CountAccumulator<T> merge(AggregateFunction.Accumulator<T> a, AggregateFunction.Accumulator<T> b) {
        CountAccumulator<T> merged = requireAccumulator(a);
        merged.count += requireAccumulator(b).count;
        return merged;
    }

    /** Accumulators come back from this same function; the cast is by construction. */
    @SuppressWarnings("unchecked")
    private static <T> CountAccumulator<T> requireAccumulator(AggregateFunction.Accumulator<T> accumulator) {
        return (CountAccumulator<T>) Objects.requireNonNull(accumulator, "accumulator");
    }

    /** Counter accumulator; one long, so a window costs O(1) memory. */
    public static final class CountAccumulator<T> implements AggregateFunction.Accumulator<T> {

        private static final long serialVersionUID = 1L;

        private long count;
    }
}
