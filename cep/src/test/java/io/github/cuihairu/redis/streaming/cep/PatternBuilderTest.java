package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class PatternBuilderTest {

    @Test
    void testSimplePattern() {
        Pattern<Integer> pattern = PatternBuilder.<Integer>create()
                .where(Pattern.of(x -> x > 5))
                .build();

        assertTrue(pattern.matches(10));
        assertFalse(pattern.matches(3));
    }

    @Test
    void testAnyPattern() {
        Pattern<Integer> pattern = PatternBuilder.<Integer>create()
                .where(Pattern.of(x -> x < 5))
                .where(Pattern.of(x -> x > 10))
                .any();

        assertTrue(pattern.matches(3));   // < 5
        assertTrue(pattern.matches(15));  // > 10
        assertFalse(pattern.matches(7));  // Neither
    }

    @Test
    void testAllPattern() {
        Pattern<Integer> pattern = PatternBuilder.<Integer>create()
                .where(Pattern.of(x -> x > 0))
                .where(Pattern.of(x -> x < 10))
                .all();

        assertTrue(pattern.matches(5));   // > 0 AND < 10
        assertFalse(pattern.matches(-1)); // Not > 0
        assertFalse(pattern.matches(15)); // Not < 10
    }

    @Test
    void testFollowedBy() {
        Pattern<Integer> pattern = PatternBuilder.<Integer>create()
                .where(Pattern.of(x -> x > 0))
                .followedBy(Pattern.of(x -> x % 2 == 0))
                .all();

        assertTrue(pattern.matches(4));   // > 0 AND even
        assertFalse(pattern.matches(3));  // > 0 but not even
    }

    @Test
    void testWithTimeWindow() {
        PatternConfig<Integer> config = PatternBuilder.<Integer>create()
                .where(Pattern.of(x -> x > 5))
                .withTimeWindow(Duration.ofMinutes(5));

        assertNotNull(config);
        assertEquals(Duration.ofMinutes(5), config.getTimeWindow());
        assertFalse(config.isContiguous());
    }

    @Test
    void testWithContiguousTimeWindow() {
        PatternConfig<Integer> config = PatternBuilder.<Integer>create()
                .where(Pattern.of(x -> x > 5))
                .withContiguousTimeWindow(Duration.ofSeconds(30));

        assertNotNull(config);
        assertEquals(Duration.ofSeconds(30), config.getTimeWindow());
        assertTrue(config.isContiguous());
    }

    @Test
    void testEmptyPattern() {
        Pattern<Integer> anyPattern = PatternBuilder.<Integer>create().any();
        Pattern<Integer> allPattern = PatternBuilder.<Integer>create().all();

        assertFalse(anyPattern.matches(5));  // Empty ANY matches nothing
        assertTrue(allPattern.matches(5));   // Empty ALL matches everything
    }
}
