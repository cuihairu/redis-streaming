package io.github.cuihairu.redis.streaming.aggregation.functions;

import io.github.cuihairu.redis.streaming.aggregation.AggregationFunction;

import java.util.Collection;

/**
 * Count aggregation function
 */
public class CountFunction implements AggregationFunction<Object, Long> {

    private static final CountFunction INSTANCE = new CountFunction();

    public static CountFunction getInstance() {
        return INSTANCE;
    }

    @Override
    public Long apply(Collection<Object> values) {
        return (long) values.size();
    }

    @Override
    public String getName() {
        return "COUNT";
    }
}