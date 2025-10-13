package io.github.cuihairu.redis.streaming.aggregation.functions;

import io.github.cuihairu.redis.streaming.aggregation.AggregationFunction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * Average aggregation function for numeric values
 */
public class AverageFunction implements AggregationFunction<Number, BigDecimal> {

    private static final AverageFunction INSTANCE = new AverageFunction();
    private final SumFunction sumFunction = SumFunction.getInstance();

    public static AverageFunction getInstance() {
        return INSTANCE;
    }

    @Override
    public BigDecimal apply(Collection<Number> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal sum = sumFunction.apply(values);
        return sum.divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
    }

    @Override
    public String getName() {
        return "AVERAGE";
    }
}