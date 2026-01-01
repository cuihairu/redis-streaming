package io.github.cuihairu.redis.streaming.aggregation.functions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MinFunction
 */
class MinFunctionTest {

    @Test
    void testCreate() {
        // Given & When
        MinFunction<Integer> minFunction1 = MinFunction.create();
        MinFunction<Integer> minFunction2 = MinFunction.create();

        // Then - should create new instances (not singleton)
        assertNotSame(minFunction1, minFunction2);
    }

    @Test
    void testApplyWithEmptyCollection() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> emptyCollection = Collections.emptyList();

        // When
        Integer result = minFunction.apply(emptyCollection);

        // Then
        assertNull(result);
    }

    @Test
    void testApplyWithSingleElement() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(5);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(5, result);
    }

    @Test
    void testApplyWithIntegers() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(1, 5, 3, 9, 2);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(1, result);
    }

    @Test
    void testApplyWithLongs() {
        // Given
        MinFunction<Long> minFunction = MinFunction.create();
        Collection<Long> numbers = List.of(10L, 50L, 30L);

        // When
        Long result = minFunction.apply(numbers);

        // Then
        assertEquals(10L, result);
    }

    @Test
    void testApplyWithDoubles() {
        // Given
        MinFunction<Double> minFunction = MinFunction.create();
        Collection<Double> numbers = List.of(1.5, 2.7, 0.9, 3.2);

        // When
        Double result = minFunction.apply(numbers);

        // Then
        assertEquals(0.9, result);
    }

    @Test
    void testApplyWithStrings() {
        // Given
        MinFunction<String> minFunction = MinFunction.create();
        Collection<String> strings = List.of("apple", "banana", "cherry");

        // When
        String result = minFunction.apply(strings);

        // Then - lexicographic minimum
        assertEquals("apple", result);
    }

    @Test
    void testApplyWithBigDecimals() {
        // Given
        MinFunction<BigDecimal> minFunction = MinFunction.create();
        Collection<BigDecimal> numbers = List.of(
                new BigDecimal("1.5"),
                new BigDecimal("2.7"),
                new BigDecimal("0.9")
        );

        // When
        BigDecimal result = minFunction.apply(numbers);

        // Then
        assertEquals(new BigDecimal("0.9"), result);
    }

    @Test
    void testApplyWithNegativeNumbers() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(-5, -10, -1, -20);

        // When
        Integer result = minFunction.apply(numbers);

        // Then - -20 is the minimum
        assertEquals(-20, result);
    }

    @Test
    void testApplyWithMixedPositiveAndNegative() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(-5, 10, -3, 8);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(-5, result);
    }

    @Test
    void testApplyWithDuplicates() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(2, 2, 3, 2, 5);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(2, result);
    }

    @Test
    void testApplyWithSameValues() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(7, 7, 7, 7);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(7, result);
    }

    @Test
    void testApplyWithZeros() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(0, -1, 1);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(-1, result);
    }

    @Test
    void testApplyWithMinInteger() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(Integer.MAX_VALUE, 0, Integer.MIN_VALUE);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(Integer.MIN_VALUE, result);
    }

    @Test
    void testApplyWithMaxInteger() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(Integer.MAX_VALUE, 100);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(100, result);
    }

    @Test
    void testApplyWithSingletonList() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> singletonList = Collections.singletonList(42);

        // When
        Integer result = minFunction.apply(singletonList);

        // Then
        assertEquals(42, result);
    }

    @Test
    void testApplyIsConsistent() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(1, 5, 3);

        // When - call multiple times
        Integer result1 = minFunction.apply(numbers);
        Integer result2 = minFunction.apply(numbers);

        // Then
        assertEquals(result1, result2);
        assertEquals(1, result1);
    }

    @Test
    void testApplyWithVerySmallNumbers() {
        // Given
        MinFunction<Long> minFunction = MinFunction.create();
        Collection<Long> numbers = List.of(Long.MIN_VALUE + 1, Long.MIN_VALUE);

        // When
        Long result = minFunction.apply(numbers);

        // Then
        assertEquals(Long.MIN_VALUE, result);
    }

    @Test
    void testGetName() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();

        // When
        String name = minFunction.getName();

        // Then
        assertEquals("MIN", name);
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

        MinFunction<Person> minFunction = MinFunction.create();
        Collection<Person> people = List.of(
                new Person("Alice", 25),
                new Person("Bob", 30),
                new Person("Charlie", 20)
        );

        // When
        Person result = minFunction.apply(people);

        // Then - Charlie has the lowest age
        assertEquals("Charlie", result.name);
        assertEquals(20, result.age);
    }

    @Test
    void testApplyWithFloats() {
        // Given
        MinFunction<Float> minFunction = MinFunction.create();
        Collection<Float> numbers = List.of(1.1f, 2.2f, 3.3f, 0.5f);

        // When
        Float result = minFunction.apply(numbers);

        // Then
        assertEquals(0.5f, result);
    }

    @Test
    void testApplyWithBytes() {
        // Given
        MinFunction<Byte> minFunction = MinFunction.create();
        Collection<Byte> numbers = List.of((byte) 1, (byte) 5, (byte) 3);

        // When
        Byte result = minFunction.apply(numbers);

        // Then
        assertEquals((byte) 1, result);
    }

    @Test
    void testApplyWithShorts() {
        // Given
        MinFunction<Short> minFunction = MinFunction.create();
        Collection<Short> numbers = List.of((short) 100, (short) 200, (short) 150);

        // When
        Short result = minFunction.apply(numbers);

        // Then
        assertEquals((short) 100, result);
    }

    @Test
    void testApplyWithDescendingOrder() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(100, 90, 80, 70, 60);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(60, result);
    }

    @Test
    void testApplyWithAscendingOrder() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(10, 20, 30, 40, 50);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(10, result);
    }

    @Test
    void testApplyWithRandomOrder() {
        // Given
        MinFunction<Integer> minFunction = MinFunction.create();
        Collection<Integer> numbers = List.of(42, 17, 99, 3, 56, 8);

        // When
        Integer result = minFunction.apply(numbers);

        // Then
        assertEquals(3, result);
    }
}
