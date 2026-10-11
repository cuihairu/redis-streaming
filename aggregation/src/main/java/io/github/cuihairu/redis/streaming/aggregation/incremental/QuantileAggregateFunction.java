package io.github.cuihairu.redis.streaming.aggregation.incremental;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A single quantile over a window as an incremental {@link AggregateFunction}, computed
 * with the same nearest-rank definition as {@code analytics.QuantileAnalyzer}
 * ({@code floor(quantile * (n - 1))} over the sorted values; quantile 1.0 is the max) —
 * the in-window case without the Redis-backed trailing window. Feed it via
 * {@code window(...).aggregate(new QuantileAggregateFunction<>(0.95))}. An empty window
 * yields {@code null}, like {@code QuantileAnalyzer.quantile}.
 *
 * <p>The ranking needs the sorted order, so the accumulator keeps every value: memory is
 * O(window size) and the result costs O(n log n) at trigger time. Use this for bounded
 * windows; for large windows or approximate answers use a sketch.
 *
 * @param <T> the input element type
 */
public class QuantileAggregateFunction<T extends Number>
        implements AggregateFunction<T, Double> {

    private static final long serialVersionUID = 1L;

    private final double quantile;

    public QuantileAggregateFunction(double quantile) {
        if (!(quantile > 0.0 && quantile <= 1.0)) {
            throw new IllegalArgumentException("quantile must be in (0, 1]");
        }
        this.quantile = quantile;
    }

    public double getQuantile() {
        return quantile;
    }

    @Override
    public QuantileAccumulator<T> createAccumulator() {
        return new QuantileAccumulator<>();
    }

    @Override
    public QuantileAccumulator<T> add(T value, AggregateFunction.Accumulator<T> accumulator) {
        Objects.requireNonNull(value, "value");
        QuantileAccumulator<T> acc = requireAccumulator(accumulator);
        acc.values.add(value.doubleValue());
        return acc;
    }

    @Override
    public Double getResult(AggregateFunction.Accumulator<T> accumulator) {
        QuantileAccumulator<T> acc = requireAccumulator(accumulator);
        if (acc.values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(acc.values);
        Collections.sort(sorted);
        int rank = (int) Math.floor(quantile * (sorted.size() - 1));
        return sorted.get(rank);
    }

    @Override
    public QuantileAccumulator<T> merge(AggregateFunction.Accumulator<T> a, AggregateFunction.Accumulator<T> b) {
        QuantileAccumulator<T> merged = requireAccumulator(a);
        merged.values.addAll(requireAccumulator(b).values);
        return merged;
    }

    /** Accumulators come back from this same function; the cast is by construction. */
    @SuppressWarnings("unchecked")
    private static <T extends Number> QuantileAccumulator<T> requireAccumulator(
            AggregateFunction.Accumulator<T> accumulator) {
        return (QuantileAccumulator<T>) Objects.requireNonNull(accumulator, "accumulator");
    }

    /** All window values; memory grows with the window size. */
    public static final class QuantileAccumulator<T> implements AggregateFunction.Accumulator<T> {

        private static final long serialVersionUID = 1L;

        private final List<Double> values = new ArrayList<>();
    }
}
