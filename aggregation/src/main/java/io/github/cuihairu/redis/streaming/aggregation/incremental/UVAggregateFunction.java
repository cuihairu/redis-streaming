package io.github.cuihairu.redis.streaming.aggregation.incremental;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Unique-visitor (UV) counting as an incremental {@link AggregateFunction}: an exact
 * distinct-element count over the window, the core-interface counterpart of
 * {@code analytics.UVCounter} without the Redis-backed trailing window or the
 * HyperLogLog approximation error.
 *
 * <p>Use this for per-window distinct counts inside a pipeline
 * ({@code window(...).aggregate(new UVAggregateFunction<>())}); use {@code UVCounter}
 * when counts must survive restarts, span processes, or tolerate approximate counting
 * for very large cardinalities. Memory is O(distinct elements in the window). Null
 * elements are rejected.
 *
 * @param <T> the input element type
 */
public class UVAggregateFunction<T> implements AggregateFunction<T, Long> {

    private static final long serialVersionUID = 1L;

    @Override
    public DistinctAccumulator<T> createAccumulator() {
        return new DistinctAccumulator<>();
    }

    @Override
    public DistinctAccumulator<T> add(T value, AggregateFunction.Accumulator<T> accumulator) {
        Objects.requireNonNull(value, "value");
        DistinctAccumulator<T> acc = requireAccumulator(accumulator);
        acc.seen.add(value);
        return acc;
    }

    @Override
    public Long getResult(AggregateFunction.Accumulator<T> accumulator) {
        return (long) requireAccumulator(accumulator).seen.size();
    }

    @Override
    public DistinctAccumulator<T> merge(AggregateFunction.Accumulator<T> a, AggregateFunction.Accumulator<T> b) {
        DistinctAccumulator<T> merged = requireAccumulator(a);
        merged.seen.addAll(requireAccumulator(b).seen);
        return merged;
    }

    /** Accumulators come back from this same function; the cast is by construction. */
    @SuppressWarnings("unchecked")
    private static <T> DistinctAccumulator<T> requireAccumulator(AggregateFunction.Accumulator<T> accumulator) {
        return (DistinctAccumulator<T>) Objects.requireNonNull(accumulator, "accumulator");
    }

    /** Distinct-element set; memory grows with the window's cardinality. */
    public static final class DistinctAccumulator<T> implements AggregateFunction.Accumulator<T> {

        private static final long serialVersionUID = 1L;

        private final Set<T> seen = new HashSet<>();
    }
}
