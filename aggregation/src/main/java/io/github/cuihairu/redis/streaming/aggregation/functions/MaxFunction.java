package io.github.cuihairu.redis.streaming.aggregation.functions;

import io.github.cuihairu.redis.streaming.aggregation.AggregationFunction;

import java.util.Collection;
import java.util.Comparator;

/**
 * Maximum aggregation function for comparable values
 */
@SuppressWarnings("deprecation") // implements the deprecated batch interface; removed with it (v2)
public class MaxFunction<T extends Comparable<T>> implements AggregationFunction<T, T> {

    @Override
    public T apply(Collection<T> values) {
        if (values.isEmpty()) {
            return null;
        }

        return values.stream()
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    @Override
    public String getName() {
        return "MAX";
    }

    /**
     * Create a new MaxFunction instance
     */
    public static <T extends Comparable<T>> MaxFunction<T> create() {
        return new MaxFunction<>();
    }
}