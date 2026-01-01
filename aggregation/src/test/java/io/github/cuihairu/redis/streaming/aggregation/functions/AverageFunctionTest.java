package io.github.cuihairu.redis.streaming.aggregation.functions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AverageFunction
 */
class AverageFunctionTest {

    @Test
    void testGetInstance() {
        // Given & When
        AverageFunction instance1 = AverageFunction.getInstance();
        AverageFunction instance2 = AverageFunction.getInstance();

        // Then - should return singleton instance
        assertSame(instance1, instance2);
    }

    @Test
    void testApplyWithEmptyCollection() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> emptyCollection = Collections.emptyList();

        // When
        BigDecimal result = averageFunction.apply(emptyCollection);

        // Then
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void testApplyWithSingleValue() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(5);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then - single value average is the value itself with 10 decimal scale
        assertEquals(0, result.compareTo(new BigDecimal("5")));
    }

    @Test
    void testApplyWithIntegers() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(2, 4, 6);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("4")));
    }

    @Test
    void testApplyWithLongs() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(10L, 20L, 30L, 40L);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("25")));
    }

    @Test
    void testApplyWithDoubles() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(1.0, 2.0, 3.0);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("2")));
    }

    @Test
    void testApplyWithDecimalResult() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(1, 2);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("1.5")));
    }

    @Test
    void testApplyWithMixedNumberTypes() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(1, 2L, 3.0);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("2")));
    }

    @Test
    void testApplyWithBigDecimals() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(
                new BigDecimal("1.5"),
                new BigDecimal("2.5"),
                new BigDecimal("3.0")
        );

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("2.3333333333")));
    }

    @Test
    void testApplyWithNegativeNumbers() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(-10, 0, 10);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void testApplyWithLargeNumbers() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(1000000L, 2000000L, 3000000L);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("2000000")));
    }

    @Test
    void testApplyWithRepeatingDecimal() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(1, 2, 3);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then - (1+2+3)/3 = 2
        assertEquals(0, result.compareTo(new BigDecimal("2")));
    }

    @Test
    void testGetName() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();

        // When
        String name = averageFunction.getName();

        // Then
        assertEquals("AVERAGE", name);
    }

    @Test
    void testApplyIsConsistent() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(1, 2, 3, 4);

        // When - call multiple times
        BigDecimal result1 = averageFunction.apply(numbers);
        BigDecimal result2 = averageFunction.apply(numbers);

        // Then
        assertEquals(result1, result2);
        assertEquals(0, result1.compareTo(new BigDecimal("2.5")));
    }

    @Test
    void testApplyWithSingletonList() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> singletonList = Collections.singletonList(42);

        // When
        BigDecimal result = averageFunction.apply(singletonList);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("42")));
    }

    @Test
    void testApplyWithVerySmallNumbers() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(
                new BigDecimal("0.001"),
                new BigDecimal("0.002"),
                new BigDecimal("0.003")
        );

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("0.002")));
    }

    @Test
    void testApplyWithRounding() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(1, 2, 2);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then - (1+2+2)/3 = 1.666... rounded to 10 decimal places
        assertEquals(0, result.compareTo(new BigDecimal("1.6666666667")));
    }

    @Test
    void testApplyWithZeros() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(0, 0, 0, 0);

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
    }

    @Test
    void testApplyWithManyValues() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        List<Integer> ints = java.util.stream.IntStream.range(1, 101).boxed().toList();
        Collection<Number> numbers = new java.util.ArrayList<>(ints);

        // When - average of 1 to 100
        BigDecimal result = averageFunction.apply(numbers);

        // Then - (1+100)*100/2 / 100 = 50.5
        assertEquals(0, result.compareTo(new BigDecimal("50.5")));
    }

    @Test
    void testApplyPrecision() {
        // Given
        AverageFunction averageFunction = AverageFunction.getInstance();
        Collection<Number> numbers = List.of(
                new BigDecimal("1.123456789"),
                new BigDecimal("2.987654321")
        );

        // When
        BigDecimal result = averageFunction.apply(numbers);

        // Then - should have 10 decimal places (scale)
        assertEquals(10, result.scale());
        assertEquals(new BigDecimal("2.0555555550"), result);
    }
}
