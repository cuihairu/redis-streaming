package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PatternTest {

    @Test
    void testSimplePattern() {
        Pattern<Integer> greaterThan5 = Pattern.of(x -> x > 5);

        assertTrue(greaterThan5.matches(10));
        assertTrue(greaterThan5.matches(6));
        assertFalse(greaterThan5.matches(5));
        assertFalse(greaterThan5.matches(3));
    }

    @Test
    void testAndPattern() {
        Pattern<Integer> greaterThan5 = Pattern.of(x -> x > 5);
        Pattern<Integer> lessThan10 = Pattern.of(x -> x < 10);
        Pattern<Integer> between5And10 = greaterThan5.and(lessThan10);

        assertTrue(between5And10.matches(7));
        assertTrue(between5And10.matches(6));
        assertFalse(between5And10.matches(5));
        assertFalse(between5And10.matches(10));
        assertFalse(between5And10.matches(3));
        assertFalse(between5And10.matches(15));
    }

    @Test
    void testOrPattern() {
        Pattern<Integer> lessThan5 = Pattern.of(x -> x < 5);
        Pattern<Integer> greaterThan10 = Pattern.of(x -> x > 10);
        Pattern<Integer> outsideRange = lessThan5.or(greaterThan10);

        assertTrue(outsideRange.matches(3));
        assertTrue(outsideRange.matches(15));
        assertFalse(outsideRange.matches(5));
        assertFalse(outsideRange.matches(7));
        assertFalse(outsideRange.matches(10));
    }

    @Test
    void testNegatePattern() {
        Pattern<Integer> greaterThan5 = Pattern.of(x -> x > 5);
        Pattern<Integer> notGreaterThan5 = greaterThan5.negate();

        assertTrue(notGreaterThan5.matches(5));
        assertTrue(notGreaterThan5.matches(3));
        assertFalse(notGreaterThan5.matches(6));
        assertFalse(notGreaterThan5.matches(10));
    }

    @Test
    void testComplexPattern() {
        Pattern<Integer> greaterThan0 = Pattern.of(x -> x > 0);
        Pattern<Integer> even = Pattern.of(x -> x % 2 == 0);
        Pattern<Integer> lessThan20 = Pattern.of(x -> x < 20);

        // Positive, even, and less than 20
        Pattern<Integer> complex = greaterThan0.and(even).and(lessThan20);

        assertTrue(complex.matches(2));
        assertTrue(complex.matches(10));
        assertTrue(complex.matches(18));
        assertFalse(complex.matches(0));   // Not > 0
        assertFalse(complex.matches(3));   // Not even
        assertFalse(complex.matches(20));  // Not < 20
        assertFalse(complex.matches(-2));  // Not > 0
    }

    @Test
    void testStringPattern() {
        Pattern<String> startsWithA = Pattern.of(s -> s.startsWith("A"));
        Pattern<String> lengthGreaterThan3 = Pattern.of(s -> s.length() > 3);

        Pattern<String> combined = startsWithA.and(lengthGreaterThan3);

        assertTrue(combined.matches("Apple"));
        assertTrue(combined.matches("Amazing"));
        assertFalse(combined.matches("App"));   // Length not > 3
        assertFalse(combined.matches("Banana")); // Doesn't start with A
    }
}
