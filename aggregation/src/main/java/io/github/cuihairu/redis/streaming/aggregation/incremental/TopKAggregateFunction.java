package io.github.cuihairu.redis.streaming.aggregation.incremental;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Top-K over a window as an incremental {@link AggregateFunction}: exact per-item
 * counts with the ranking computed at trigger time. The core-interface counterpart of
 * {@code analytics.TopKAnalyzer} without the Redis-backed trailing window; feed it to a
 * pipeline window via {@code window(...).aggregate(new TopKAggregateFunction<>(10))}.
 *
 * <p>Ranking is count descending with ties broken by item ascending — the same
 * deterministic tie policy as {@code TopKAnalyzer} — and the result carries each item's
 * window count. Counts are exact and kept for every distinct item, so memory is
 * O(distinct items in the window); use {@code TopKAnalyzer} (bounded buckets) or a
 * sketch when the window's cardinality is the concern.
 *
 * @param <T> the item type, comparable so ties resolve deterministically
 */
public class TopKAggregateFunction<T extends Comparable<T>>
        implements AggregateFunction<T, List<TopKAggregateFunction.TopKEntry<T>>> {

    private static final long serialVersionUID = 1L;

    private final int k;

    public TopKAggregateFunction(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive");
        }
        this.k = k;
    }

    public int getK() {
        return k;
    }

    @Override
    public TopKAccumulator<T> createAccumulator() {
        return new TopKAccumulator<>();
    }

    @Override
    public TopKAccumulator<T> add(T value, AggregateFunction.Accumulator<T> accumulator) {
        Objects.requireNonNull(value, "value");
        TopKAccumulator<T> acc = requireAccumulator(accumulator);
        acc.counts.merge(value, 1L, Long::sum);
        return acc;
    }

    @Override
    public List<TopKEntry<T>> getResult(AggregateFunction.Accumulator<T> accumulator) {
        List<Map.Entry<T, Long>> entries = new ArrayList<>(requireAccumulator(accumulator).counts.entrySet());
        entries.sort(rankOrder());
        int limit = Math.min(k, entries.size());
        List<TopKEntry<T>> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Map.Entry<T, Long> entry = entries.get(i);
            result.add(new TopKEntry<>(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    @Override
    public TopKAccumulator<T> merge(AggregateFunction.Accumulator<T> a, AggregateFunction.Accumulator<T> b) {
        TopKAccumulator<T> merged = requireAccumulator(a);
        for (Map.Entry<T, Long> entry : requireAccumulator(b).counts.entrySet()) {
            merged.counts.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        return merged;
    }

    /** Accumulators come back from this same function; the cast is by construction. */
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> TopKAccumulator<T> requireAccumulator(
            AggregateFunction.Accumulator<T> accumulator) {
        return (TopKAccumulator<T>) Objects.requireNonNull(accumulator, "accumulator");
    }

    /** Query ranking: count descending, ties broken by item ascending. */
    private static <T extends Comparable<? super T>> Comparator<Map.Entry<T, Long>> rankOrder() {
        return Map.Entry.<T, Long>comparingByValue(Comparator.<Long>reverseOrder())
                .thenComparing(Map.Entry.comparingByKey());
    }

    /** One ranked item with its window count. */
    public record TopKEntry<T>(T item, long count) implements Serializable {
    }

    /** Exact per-item counts; memory grows with the window's cardinality. */
    public static final class TopKAccumulator<T> implements AggregateFunction.Accumulator<T> {

        private static final long serialVersionUID = 1L;

        private final Map<T, Long> counts = new HashMap<>();
    }
}
