package io.github.cuihairu.redis.streaming.aggregation.functions;

import io.github.cuihairu.redis.streaming.aggregation.AggregationFunction;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * Sum aggregation function for numeric values
 */
public class SumFunction implements AggregationFunction<Number, BigDecimal> {

    private static final SumFunction INSTANCE = new SumFunction();

    public static SumFunction getInstance() {
        return INSTANCE;
    }

    @Override
    public BigDecimal apply(Collection<Number> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return values.stream()
                .map(this::toBigDecimal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public String getName() {
        return "SUM";
    }

    private BigDecimal toBigDecimal(Number number) {
        if (number instanceof BigDecimal) {
            return (BigDecimal) number;
        } else if (number instanceof Double || number instanceof Float) {
            return BigDecimal.valueOf(number.doubleValue());
        } else {
            return BigDecimal.valueOf(number.longValue());
        }
    }
}