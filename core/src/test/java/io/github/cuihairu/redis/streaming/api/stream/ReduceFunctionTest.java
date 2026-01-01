package io.github.cuihairu.redis.streaming.api.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReduceFunction
 */
class ReduceFunctionTest {

    @Test
    void testIntegerSum() throws Exception {
        // Given
        ReduceFunction<Integer> sumReduce = Integer::sum;

        // When
        int result = sumReduce.reduce(5, 3);

        // Then
        assertEquals(8, result);
    }

    @Test
    void testIntegerMax() throws Exception {
        // Given
        ReduceFunction<Integer> maxReduce = Math::max;

        // When
        int result = maxReduce.reduce(10, 20);

        // Then
        assertEquals(20, result);
    }

    @Test
    void testIntegerMin() throws Exception {
        // Given
        ReduceFunction<Integer> minReduce = Math::min;

        // When
        int result = minReduce.reduce(10, 20);

        // Then
        assertEquals(10, result);
    }

    @Test
    void testStringConcatenation() throws Exception {
        // Given
        ReduceFunction<String> concatReduce = String::concat;

        // When
        String result = concatReduce.reduce("Hello, ", "World!");

        // Then
        assertEquals("Hello, World!", result);
    }

    @Test
    void testLongSum() throws Exception {
        // Given
        ReduceFunction<Long> sumReduce = Long::sum;

        // When
        long result = sumReduce.reduce(1000L, 2000L);

        // Then
        assertEquals(3000L, result);
    }

    @Test
    void testDoubleSum() throws Exception {
        // Given
        ReduceFunction<Double> sumReduce = Double::sum;

        // When
        double result = sumReduce.reduce(1.5, 2.5);

        // Then
        assertEquals(4.0, result, 0.001);
    }

    @Test
    void testCustomObjectReduction() throws Exception {
        // Given
        class Person {
            final String name;
            final int age;
            Person(String name, int age) {
                this.name = name;
                this.age = age;
            }
            Person merge(Person other) {
                return new Person(this.name + "+" + other.name, this.age + other.age);
            }
        }

        ReduceFunction<Person> mergeReduce = Person::merge;
        Person p1 = new Person("Alice", 25);
        Person p2 = new Person("Bob", 30);

        // When
        Person result = mergeReduce.reduce(p1, p2);

        // Then
        assertEquals("Alice+Bob", result.name);
        assertEquals(55, result.age);
    }

    @Test
    void testLambdaImplementation() throws Exception {
        // Given
        ReduceFunction<Integer> multiplyReduce = (a, b) -> a * b;

        // When
        int result = multiplyReduce.reduce(6, 7);

        // Then
        assertEquals(42, result);
    }

    @Test
    void testMethodReference() throws Exception {
        // Given - method reference that takes two parameters
        ReduceFunction<String> reduce = String::concat;

        // When
        String result = reduce.reduce("Hello", "World");

        // Then
        assertEquals("HelloWorld", result);
    }

    @Test
    void testLambdaWrappingMethodReference() throws Exception {
        // Given - lambda that wraps single-parameter method references
        ReduceFunction<String> reduce = (s1, s2) -> s1.toLowerCase() + s2.toLowerCase();

        // When
        String result = reduce.reduce("HELLO", "WORLD");

        // Then
        assertEquals("helloworld", result);
    }

    @Test
    void testReduceWithSameValues() throws Exception {
        // Given
        ReduceFunction<Integer> sumReduce = Integer::sum;

        // When
        int result = sumReduce.reduce(10, 10);

        // Then
        assertEquals(20, result);
    }

    @Test
    void testReduceWithZero() throws Exception {
        // Given
        ReduceFunction<Integer> sumReduce = Integer::sum;

        // When
        int result = sumReduce.reduce(0, 100);

        // Then
        assertEquals(100, result);
    }

    @Test
    void testReduceWithNegativeNumbers() throws Exception {
        // Given
        ReduceFunction<Integer> sumReduce = Integer::sum;

        // When
        int result = sumReduce.reduce(-10, -20);

        // Then
        assertEquals(-30, result);
    }

    @Test
    void testReduceWithMixedSignNumbers() throws Exception {
        // Given
        ReduceFunction<Integer> sumReduce = Integer::sum;

        // When
        int result = sumReduce.reduce(-10, 20);

        // Then
        assertEquals(10, result);
    }

    @Test
    void testReduceThrowsException() {
        // Given
        ReduceFunction<Integer> failingReduce = (a, b) -> {
            if (b == 0) {
                throw new ArithmeticException("Cannot reduce with zero");
            }
            return a / b;
        };

        // When & Then
        assertThrows(ArithmeticException.class, () -> failingReduce.reduce(10, 0));
    }

    @Test
    void testChainedReduction() throws Exception {
        // Given
        ReduceFunction<Integer> sumReduce = Integer::sum;

        // When - chain multiple reductions
        int result1 = sumReduce.reduce(1, 2);
        int result2 = sumReduce.reduce(result1, 3);
        int result3 = sumReduce.reduce(result2, 4);

        // Then
        assertEquals(10, result3);
    }

    @Test
    void testReduceWithNullFirstValue() throws Exception {
        // Given
        ReduceFunction<String> reduce = (a, b) -> a == null ? b : a + b;

        // When
        String result = reduce.reduce(null, "Hello");

        // Then
        assertEquals("Hello", result);
    }

    @Test
    void testReduceWithNullSecondValue() throws Exception {
        // Given
        ReduceFunction<String> reduce = (a, b) -> b == null ? a : a + b;

        // When
        String result = reduce.reduce("Hello", null);

        // Then
        assertEquals("Hello", result);
    }

    @Test
    void testReduceWithBothNull() throws Exception {
        // Given
        ReduceFunction<String> reduce = (a, b) -> {
            if (a == null && b == null) return "both-null";
            if (a == null) return b;
            if (b == null) return a;
            return a + b;
        };

        // When
        String result = reduce.reduce(null, null);

        // Then
        assertEquals("both-null", result);
    }

    @Test
    void testReduceIsFunctionalInterface() {
        // Given & When & Then - ReduceFunction should be a functional interface
        assertTrue(ReduceFunction.class.isAnnotationPresent(FunctionalInterface.class));
    }

    @Test
    void testReduceIsSerializable() {
        // Given - a simple reduce function
        ReduceFunction<Integer> sumReduce = Integer::sum;

        // When & Then - should be serializable
        assertTrue(sumReduce instanceof java.io.Serializable);
    }

    @Test
    void testReduceWithMaxValue() throws Exception {
        // Given
        ReduceFunction<Integer> sumReduce = Integer::sum;

        // When
        int result = sumReduce.reduce(Integer.MAX_VALUE, 1);

        // Then - will overflow, but should not throw exception
        assertTrue(result < 0); // Overflow result
    }

    @Test
    void testReduceWithMinValue() throws Exception {
        // Given
        ReduceFunction<Integer> sumReduce = Integer::sum;

        // When
        int result = sumReduce.reduce(Integer.MIN_VALUE, -1);

        // Then - will overflow
        assertTrue(result > 0); // Overflow result
    }

    @Test
    void testBooleanAndReduce() throws Exception {
        // Given
        ReduceFunction<Boolean> andReduce = (a, b) -> a && b;

        // When
        boolean result = andReduce.reduce(true, false);

        // Then
        assertFalse(result);
    }

    @Test
    void testBooleanOrReduce() throws Exception {
        // Given
        ReduceFunction<Boolean> orReduce = (a, b) -> a || b;

        // When
        boolean result = orReduce.reduce(false, true);

        // Then
        assertTrue(result);
    }

    @Test
    void testCustomComplexReduction() throws Exception {
        // Given - reduce that finds the longer string and concatenates
        ReduceFunction<String> longerFirstReduce = (a, b) -> {
            if (a.length() >= b.length()) {
                return a + b;
            } else {
                return b + a;
            }
        };

        // When
        String result = longerFirstReduce.reduce("Hi", "Hello");

        // Then - "Hello" is longer, so it comes first
        assertEquals("HelloHi", result);
    }
}
