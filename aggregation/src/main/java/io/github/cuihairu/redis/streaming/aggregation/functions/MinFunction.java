package io.github.cuihairu.redis.streaming.aggregation.functions;

import io.github.cuihairu.redis.streaming.aggregation.AggregationFunction;

import java.util.Collection;
import java.util.Comparator;

/**
 * Minimum aggregation function for comparable values
 */
public class MinFunction<T extends Comparable<T>> implements AggregationFunction<T, T> {

    @Override
    public T apply(Collection<T> values) {
        if (values.isEmpty()) {
            return null;
        }

        return values.stream()
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    @Override
    public String getName() {
        return "MIN";
    }

    /**
     * Create a new MinFunction instance
     */
    public static <T extends Comparable<T>> MinFunction<T> create() {
        return new MinFunction<>();
    }
}