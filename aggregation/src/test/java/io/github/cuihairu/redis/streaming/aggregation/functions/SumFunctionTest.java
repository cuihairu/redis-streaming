package io.github.cuihairu.redis.streaming.aggregation.functions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SumFunction
 */
class SumFunctionTest {

    @Test
    void testGetInstance() {
        // Given & When
        SumFunction instance1 = SumFunction.getInstance();
        SumFunction instance2 = SumFunction.getInstance();

        // Then - should return singleton instance
        assertSame(instance1, instance2);
    }

    @Test
    void testApplyWithEmptyCollection() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> emptyCollection = Collections.emptyList();

        // When
        BigDecimal result = sumFunction.apply(emptyCollection);

        // Then
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void testApplyWithSingleInteger() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(5);

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("5"), result);
    }

    @Test
    void testApplyWithMultipleIntegers() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(1, 2, 3, 4, 5);

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("15"), result);
    }

    @Test
    void testApplyWithLongs() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(10L, 20L, 30L);

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("60"), result);
    }

    @Test
    void testApplyWithDoubles() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(1.5, 2.5, 3.0);

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("7.0"), result);
    }

    @Test
    void testApplyWithMixedNumberTypes() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(1, 2L, 3.5, 4.25f);

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(0, result.compareTo(new BigDecimal("10.75")));
    }

    @Test
    void testApplyWithBigDecimals() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(
                new BigDecimal("1.10"),
                new BigDecimal("2.20"),
                new BigDecimal("3.30")
        );

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("6.60"), result);
    }

    @Test
    void testApplyWithNegativeNumbers() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(-5, 10, -3, 8);

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("10"), result);
    }

    @Test
    void testApplyWithZeros() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(0, 0, 0, 0);

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    void testApplyWithLargeNumbers() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(Long.MAX_VALUE, 1L);

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then - Long.MAX_VALUE + 1
        assertEquals(new BigDecimal(Long.MAX_VALUE).add(BigDecimal.ONE), result);
    }

    @Test
    void testApplyWithDecimalPrecision() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(
                new BigDecimal("0.1"),
                new BigDecimal("0.2"),
                new BigDecimal("0.3")
        );

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then - BigDecimal maintains precision
        assertEquals(new BigDecimal("0.6"), result);
    }

    @Test
    void testGetName() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();

        // When
        String name = sumFunction.getName();

        // Then
        assertEquals("SUM", name);
    }

    @Test
    void testApplyIsConsistent() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(1, 2, 3);

        // When - call multiple times
        BigDecimal result1 = sumFunction.apply(numbers);
        BigDecimal result2 = sumFunction.apply(numbers);

        // Then
        assertEquals(result1, result2);
        assertEquals(new BigDecimal("6"), result1);
    }

    @Test
    void testApplyWithSingletonList() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> singletonList = Collections.singletonList(42);

        // When
        BigDecimal result = sumFunction.apply(singletonList);

        // Then
        assertEquals(new BigDecimal("42"), result);
    }

    @Test
    void testApplyWithVerySmallDecimals() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(
                new BigDecimal("0.0001"),
                new BigDecimal("0.0002"),
                new BigDecimal("0.0003")
        );

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("0.0006"), result);
    }

    @Test
    void testApplyWithMixedPositiveAndNegative() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(100, -50, 25, -25);

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("50"), result);
    }

    @Test
    void testApplyWithByteAndShort() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        byte b = 10;
        short s = 20;

        // When
        BigDecimal result = sumFunction.apply(List.of((Number) b, (Number) s));

        // Then
        assertEquals(new BigDecimal("30"), result);
    }

    @Test
    void testBigDecimalValuesArePreserved() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        BigDecimal bd1 = new BigDecimal("123.456");
        BigDecimal bd2 = new BigDecimal("789.012");

        // When
        BigDecimal result = sumFunction.apply(List.of((Number) bd1, (Number) bd2));

        // Then
        assertEquals(new BigDecimal("912.468"), result);
    }

    @Test
    void testDoubleConversion() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        double value = 123.456;

        // When
        BigDecimal result = sumFunction.apply(List.of(value));

        // Then - double is converted to BigDecimal
        assertEquals(0, result.compareTo(new BigDecimal(Double.toString(value))));
    }

    @Test
    void testApplyWithManyDecimals() {
        // Given
        SumFunction sumFunction = SumFunction.getInstance();
        Collection<Number> numbers = List.of(
                new BigDecimal("1.1"),
                new BigDecimal("2.2"),
                new BigDecimal("3.3"),
                new BigDecimal("4.4"),
                new BigDecimal("5.5")
        );

        // When
        BigDecimal result = sumFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("16.5"), result);
    }
}
