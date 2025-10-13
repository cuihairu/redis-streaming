package io.github.cuihairu.redis.streaming.aggregation.functions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AggregationFunctionsTest {

    @Test
    void testCountFunction() {
        CountFunction countFunction = CountFunction.getInstance();

        assertEquals(0L, countFunction.apply(Collections.emptyList()));
        assertEquals(3L, countFunction.apply(Arrays.asList("a", "b", "c")));
        assertEquals(5L, countFunction.apply(Arrays.asList(1, 2, 3, 4, 5)));
        assertEquals("COUNT", countFunction.getName());
    }

    @Test
    void testSumFunction() {
        SumFunction sumFunction = SumFunction.getInstance();

        assertEquals(BigDecimal.ZERO, sumFunction.apply(Collections.emptyList()));

        List<Number> integers = Arrays.asList(1, 2, 3, 4, 5);
        assertEquals(new BigDecimal("15"), sumFunction.apply(integers));

        List<Number> decimals = Arrays.asList(1.5, 2.5, 3.0);
        assertEquals(new BigDecimal("7.0"), sumFunction.apply(decimals));

        assertEquals("SUM", sumFunction.getName());
    }

    @Test
    void testAverageFunction() {
        AverageFunction avgFunction = AverageFunction.getInstance();

        assertEquals(BigDecimal.ZERO, avgFunction.apply(Collections.emptyList()));

        List<Number> numbers = Arrays.asList(1, 2, 3, 4, 5);
        BigDecimal average = avgFunction.apply(numbers);
        assertEquals(new BigDecimal("3.0000000000"), average);

        List<Number> decimals = Arrays.asList(2.0, 4.0, 6.0);
        BigDecimal avgDecimal = avgFunction.apply(decimals);
        assertEquals(new BigDecimal("4.0000000000"), avgDecimal);

        assertEquals("AVERAGE", avgFunction.getName());
    }

    @Test
    void testMinFunction() {
        MinFunction<Integer> minFunction = MinFunction.create();

        assertNull(minFunction.apply(Collections.emptyList()));

        List<Integer> numbers = Arrays.asList(5, 2, 8, 1, 9);
        assertEquals(Integer.valueOf(1), minFunction.apply(numbers));

        List<Integer> singleNumber = Arrays.asList(42);
        assertEquals(Integer.valueOf(42), minFunction.apply(singleNumber));

        assertEquals("MIN", minFunction.getName());
    }

    @Test
    void testMaxFunction() {
        MaxFunction<Integer> maxFunction = MaxFunction.create();

        assertNull(maxFunction.apply(Collections.emptyList()));

        List<Integer> numbers = Arrays.asList(5, 2, 8, 1, 9);
        assertEquals(Integer.valueOf(9), maxFunction.apply(numbers));

        List<Integer> singleNumber = Arrays.asList(42);
        assertEquals(Integer.valueOf(42), maxFunction.apply(singleNumber));

        assertEquals("MAX", maxFunction.getName());
    }

    @Test
    void testMinMaxWithStrings() {
        MinFunction<String> minFunction = MinFunction.create();
        MaxFunction<String> maxFunction = MaxFunction.create();

        List<String> strings = Arrays.asList("apple", "banana", "cherry", "date");

        assertEquals("apple", minFunction.apply(strings));
        assertEquals("date", maxFunction.apply(strings));
    }

    @Test
    void testSumWithMixedNumberTypes() {
        SumFunction sumFunction = SumFunction.getInstance();

        List<Number> mixedNumbers = Arrays.asList(
                1,              // Integer
                2.5,            // Double
                3.0f,           // Float
                4L,             // Long
                new BigDecimal("5.5")  // BigDecimal
        );

        BigDecimal result = sumFunction.apply(mixedNumbers);
        assertEquals(new BigDecimal("16.0"), result);
    }
}