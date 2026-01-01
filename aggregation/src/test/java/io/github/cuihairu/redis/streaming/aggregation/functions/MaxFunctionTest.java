package io.github.cuihairu.redis.streaming.aggregation.functions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MaxFunction
 */
class MaxFunctionTest {

    @Test
    void testCreate() {
        // Given & When
        MaxFunction<Integer> maxFunction1 = MaxFunction.create();
        MaxFunction<Integer> maxFunction2 = MaxFunction.create();

        // Then - should create new instances (not singleton)
        assertNotSame(maxFunction1, maxFunction2);
    }

    @Test
    void testApplyWithEmptyCollection() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> emptyCollection = Collections.emptyList();

        // When
        Integer result = maxFunction.apply(emptyCollection);

        // Then
        assertNull(result);
    }

    @Test
    void testApplyWithSingleElement() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(5);

        // When
        Integer result = maxFunction.apply(numbers);

        // Then
        assertEquals(5, result);
    }

    @Test
    void testApplyWithIntegers() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(1, 5, 3, 9, 2);

        // When
        Integer result = maxFunction.apply(numbers);

        // Then
        assertEquals(9, result);
    }

    @Test
    void testApplyWithLongs() {
        // Given
        MaxFunction<Long> maxFunction = MaxFunction.create();
        Collection<Long> numbers = List.of(10L, 50L, 30L);

        // When
        Long result = maxFunction.apply(numbers);

        // Then
        assertEquals(50L, result);
    }

    @Test
    void testApplyWithDoubles() {
        // Given
        MaxFunction<Double> maxFunction = MaxFunction.create();
        Collection<Double> numbers = List.of(1.5, 2.7, 0.9, 3.2);

        // When
        Double result = maxFunction.apply(numbers);

        // Then
        assertEquals(3.2, result);
    }

    @Test
    void testApplyWithStrings() {
        // Given
        MaxFunction<String> maxFunction = MaxFunction.create();
        Collection<String> strings = List.of("apple", "banana", "cherry");

        // When
        String result = maxFunction.apply(strings);

        // Then - lexicographic maximum
        assertEquals("cherry", result);
    }

    @Test
    void testApplyWithBigDecimals() {
        // Given
        MaxFunction<BigDecimal> maxFunction = MaxFunction.create();
        Collection<BigDecimal> numbers = List.of(
                new BigDecimal("1.5"),
                new BigDecimal("2.7"),
                new BigDecimal("0.9")
        );

        // When
        BigDecimal result = maxFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("2.7"), result);
    }

    @Test
    void testApplyWithNegativeNumbers() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(-5, -10, -1, -20);

        // When
        Integer result = maxFunction.apply(numbers);

        // Then - -1 is the maximum
        assertEquals(-1, result);
    }

    @Test
    void testApplyWithMixedPositiveAndNegative() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(-5, 10, -3, 8);

        // When
        Integer result = maxFunction.apply(numbers);

        // Then
        assertEquals(10, result);
    }

    @Test
    void testApplyWithDuplicates() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(5, 5, 3, 5, 2);

        // When
        Integer result = maxFunction.apply(numbers);

        // Then
        assertEquals(5, result);
    }

    @Test
    void testApplyWithSameValues() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(7, 7, 7, 7);

        // When
        Integer result = maxFunction.apply(numbers);

        // Then
        assertEquals(7, result);
    }

    @Test
    void testApplyWithZeros() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(0, -1, 1);

        // When
        Integer result = maxFunction.apply(numbers);

        // Then
        assertEquals(1, result);
    }

    @Test
    void testApplyWithMaxInteger() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(Integer.MAX_VALUE, 0, Integer.MIN_VALUE);

        // When
        Integer result = maxFunction.apply(numbers);

        // Then
        assertEquals(Integer.MAX_VALUE, result);
    }

    @Test
    void testApplyWithMinInteger() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(Integer.MIN_VALUE, -100);

        // When
        Integer result = maxFunction.apply(numbers);

        // Then
        assertEquals(-100, result);
    }

    @Test
    void testApplyWithSingletonList() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> singletonList = Collections.singletonList(42);

        // When
        Integer result = maxFunction.apply(singletonList);

        // Then
        assertEquals(42, result);
    }

    @Test
    void testApplyIsConsistent() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();
        Collection<Integer> numbers = List.of(1, 5, 3);

        // When - call multiple times
        Integer result1 = maxFunction.apply(numbers);
        Integer result2 = maxFunction.apply(numbers);

        // Then
        assertEquals(result1, result2);
        assertEquals(5, result1);
    }

    @Test
    void testApplyWithVeryLargeNumbers() {
        // Given
        MaxFunction<Long> maxFunction = MaxFunction.create();
        Collection<Long> numbers = List.of(Long.MAX_VALUE - 1, Long.MAX_VALUE);

        // When
        Long result = maxFunction.apply(numbers);

        // Then
        assertEquals(Long.MAX_VALUE, result);
    }

    @Test
    void testGetName() {
        // Given
        MaxFunction<Integer> maxFunction = MaxFunction.create();

        // When
        String name = maxFunction.getName();

        // Then
        assertEquals("MAX", name);
    }

    @Test
    void testApplyWithComparableObjects() {
        // Given - custom comparable class
        class Person implements Comparable<Person> {
            private final String name;
            private final int age;

            Person(String name, int age) {
                this.name = name;
                this.age = age;
            }

            @Override
            public int compareTo(Person other) {
                return Integer.compare(this.age, other.age);
            }

            @Override
            public String toString() {
                return name + "(" + age + ")";
            }
        }

        MaxFunction<Person> maxFunction = MaxFunction.create();
        Collection<Person> people = List.of(
                new Person("Alice", 25),
                new Person("Bob", 30),
                new Person("Charlie", 20)
        );

        // When
        Person result = maxFunction.apply(people);

        // Then - Bob has the highest age
        assertEquals("Bob", result.name);
        assertEquals(30, result.age);
    }

    @Test
    void testApplyWithFloats() {
        // Given
        MaxFunction<Float> maxFunction = MaxFunction.create();
        Collection<Float> numbers = List.of(1.1f, 2.2f, 3.3f, 0.5f);

        // When
        Float result = maxFunction.apply(numbers);

        // Then
        assertEquals(3.3f, result);
    }

    @Test
    void testApplyWithBytes() {
        // Given
        MaxFunction<Byte> maxFunction = MaxFunction.create();
        Collection<Byte> numbers = List.of((byte) 1, (byte) 5, (byte) 3);

        // When
        Byte result = maxFunction.apply(numbers);

        // Then
        assertEquals((byte) 5, result);
    }

    @Test
    void testApplyWithShorts() {
        // Given
        MaxFunction<Short> maxFunction = MaxFunction.create();
        Collection<Short> numbers = List.of((short) 100, (short) 200, (short) 150);

        // When
        Short result = maxFunction.apply(numbers);

        // Then
        assertEquals((short) 200, result);
    }
}
