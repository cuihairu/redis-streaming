package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PatternQuantifier
 */
class PatternQuantifierTest {

    // ===== exactly Tests =====

    @Test
    void testExactlyWithPositiveNumber() {
        PatternQuantifier q = PatternQuantifier.exactly(5);

        assertEquals(PatternQuantifier.QuantifierType.EXACTLY, q.getType());
        assertEquals(5, q.getMinOccurrences());
        assertEquals(5, q.getMaxOccurrences());
        assertTrue(q.matches(5));
        assertFalse(q.matches(4));
        assertFalse(q.matches(6));
    }

    @Test
    void testExactlyWithOne() {
        PatternQuantifier q = PatternQuantifier.exactly(1);

        assertEquals(1, q.getMinOccurrences());
        assertEquals(1, q.getMaxOccurrences());
        assertTrue(q.matches(1));
        assertFalse(q.matches(0));
        assertFalse(q.matches(2));
    }

    @Test
    void testExactlyWithZeroThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> PatternQuantifier.exactly(0));
    }

    @Test
    void testExactlyWithNegativeThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> PatternQuantifier.exactly(-1));
    }

    @Test
    void testExactlyToString() {
        assertEquals("{1}", PatternQuantifier.exactly(1).toString());
        assertEquals("{5}", PatternQuantifier.exactly(5).toString());
        assertEquals("{100}", PatternQuantifier.exactly(100).toString());
    }

    // ===== oneOrMore Tests =====

    @Test
    void testOneOrMore() {
        PatternQuantifier q = PatternQuantifier.oneOrMore();

        assertEquals(PatternQuantifier.QuantifierType.ONE_OR_MORE, q.getType());
        assertEquals(1, q.getMinOccurrences());
        assertEquals(Integer.MAX_VALUE, q.getMaxOccurrences());
        assertTrue(q.matches(1));
        assertTrue(q.matches(5));
        assertTrue(q.matches(100));
        assertTrue(q.matches(Integer.MAX_VALUE));
        assertFalse(q.matches(0));
    }

    @Test
    void testOneOrMoreToString() {
        assertEquals("+", PatternQuantifier.oneOrMore().toString());
    }

    // ===== zeroOrMore Tests =====

    @Test
    void testZeroOrMore() {
        PatternQuantifier q = PatternQuantifier.zeroOrMore();

        assertEquals(PatternQuantifier.QuantifierType.ZERO_OR_MORE, q.getType());
        assertEquals(0, q.getMinOccurrences());
        assertEquals(Integer.MAX_VALUE, q.getMaxOccurrences());
        assertTrue(q.matches(0));
        assertTrue(q.matches(1));
        assertTrue(q.matches(5));
        assertTrue(q.matches(100));
    }

    @Test
    void testZeroOrMoreToString() {
        assertEquals("*", PatternQuantifier.zeroOrMore().toString());
    }

    // ===== optional Tests =====

    @Test
    void testOptional() {
        PatternQuantifier q = PatternQuantifier.optional();

        assertEquals(PatternQuantifier.QuantifierType.OPTIONAL, q.getType());
        assertEquals(0, q.getMinOccurrences());
        assertEquals(1, q.getMaxOccurrences());
        assertTrue(q.matches(0));
        assertTrue(q.matches(1));
        assertFalse(q.matches(2));
        assertFalse(q.matches(5));
    }

    @Test
    void testOptionalToString() {
        assertEquals("?", PatternQuantifier.optional().toString());
    }

    // ===== times (range) Tests =====

    @Test
    void testTimesWithValidRange() {
        PatternQuantifier q = PatternQuantifier.times(2, 5);

        assertEquals(PatternQuantifier.QuantifierType.RANGE, q.getType());
        assertEquals(2, q.getMinOccurrences());
        assertEquals(5, q.getMaxOccurrences());
        assertFalse(q.matches(1));
        assertTrue(q.matches(2));
        assertTrue(q.matches(3));
        assertTrue(q.matches(4));
        assertTrue(q.matches(5));
        assertFalse(q.matches(6));
    }

    @Test
    void testTimesWithSameMinMax() {
        PatternQuantifier q = PatternQuantifier.times(3, 3);

        assertEquals(3, q.getMinOccurrences());
        assertEquals(3, q.getMaxOccurrences());
        assertTrue(q.matches(3));
        assertFalse(q.matches(2));
        assertFalse(q.matches(4));
    }

    @Test
    void testTimesWithZeroMin() {
        PatternQuantifier q = PatternQuantifier.times(0, 3);

        assertEquals(0, q.getMinOccurrences());
        assertEquals(3, q.getMaxOccurrences());
        assertTrue(q.matches(0));
        assertTrue(q.matches(1));
        assertTrue(q.matches(2));
        assertTrue(q.matches(3));
        assertFalse(q.matches(4));
    }

    @Test
    void testTimesWithNegativeMinThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> PatternQuantifier.times(-1, 5));
    }

    @Test
    void testTimesWithMaxLessThanMinThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> PatternQuantifier.times(5, 3));
    }

    @Test
    void testTimesWithEqualMinMaxThrowsNoException() {
        assertDoesNotThrow(() -> PatternQuantifier.times(5, 5));
    }

    @Test
    void testTimesToString() {
        assertEquals("{2,5}", PatternQuantifier.times(2, 5).toString());
        assertEquals("{0,1}", PatternQuantifier.times(0, 1).toString());
        assertEquals("{10,20}", PatternQuantifier.times(10, 20).toString());
    }

    // ===== atLeast Tests =====

    @Test
    void testAtLeastWithPositiveNumber() {
        PatternQuantifier q = PatternQuantifier.atLeast(5);

        assertEquals(PatternQuantifier.QuantifierType.AT_LEAST, q.getType());
        assertEquals(5, q.getMinOccurrences());
        assertEquals(Integer.MAX_VALUE, q.getMaxOccurrences());
        assertFalse(q.matches(4));
        assertTrue(q.matches(5));
        assertTrue(q.matches(6));
        assertTrue(q.matches(100));
    }

    @Test
    void testAtLeastWithZero() {
        PatternQuantifier q = PatternQuantifier.atLeast(0);

        assertEquals(0, q.getMinOccurrences());
        assertEquals(Integer.MAX_VALUE, q.getMaxOccurrences());
        assertTrue(q.matches(0));
        assertTrue(q.matches(1));
        assertTrue(q.matches(100));
    }

    @Test
    void testAtLeastWithNegativeThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> PatternQuantifier.atLeast(-1));
    }

    @Test
    void testAtLeastToString() {
        assertEquals("{0,}", PatternQuantifier.atLeast(0).toString());
        assertEquals("{1,}", PatternQuantifier.atLeast(1).toString());
        assertEquals("{5,}", PatternQuantifier.atLeast(5).toString());
    }

    // ===== matches Edge Cases Tests =====

    @Test
    void testMatchesWithBoundaryValues() {
        PatternQuantifier exactly = PatternQuantifier.exactly(10);
        assertTrue(exactly.matches(10));
        assertFalse(exactly.matches(9));
        assertFalse(exactly.matches(11));

        PatternQuantifier range = PatternQuantifier.times(5, 10);
        assertTrue(range.matches(5));
        assertTrue(range.matches(10));
        assertFalse(range.matches(4));
        assertFalse(range.matches(11));

        PatternQuantifier atLeast = PatternQuantifier.atLeast(5);
        assertTrue(atLeast.matches(5));
        assertTrue(atLeast.matches(Integer.MAX_VALUE));
        assertFalse(atLeast.matches(4));
    }

    @Test
    void testMatchesWithZero() {
        assertFalse(PatternQuantifier.oneOrMore().matches(0));
        assertTrue(PatternQuantifier.zeroOrMore().matches(0));
        assertTrue(PatternQuantifier.optional().matches(0));
        assertFalse(PatternQuantifier.exactly(1).matches(0));
        assertTrue(PatternQuantifier.times(0, 5).matches(0));
        assertTrue(PatternQuantifier.atLeast(0).matches(0));
    }

    @Test
    void testMatchesWithLargeNumbers() {
        PatternQuantifier q = PatternQuantifier.times(100, 200);
        assertTrue(q.matches(100));
        assertTrue(q.matches(150));
        assertTrue(q.matches(200));
        assertFalse(q.matches(99));
        assertFalse(q.matches(201));
    }

    // ===== getType Tests =====

    @Test
    void testGetTypeReturnsCorrectType() {
        assertEquals(PatternQuantifier.QuantifierType.EXACTLY, PatternQuantifier.exactly(5).getType());
        assertEquals(PatternQuantifier.QuantifierType.ONE_OR_MORE, PatternQuantifier.oneOrMore().getType());
        assertEquals(PatternQuantifier.QuantifierType.ZERO_OR_MORE, PatternQuantifier.zeroOrMore().getType());
        assertEquals(PatternQuantifier.QuantifierType.OPTIONAL, PatternQuantifier.optional().getType());
        assertEquals(PatternQuantifier.QuantifierType.RANGE, PatternQuantifier.times(2, 5).getType());
        assertEquals(PatternQuantifier.QuantifierType.AT_LEAST, PatternQuantifier.atLeast(3).getType());
    }

    // ===== Getters Tests =====

    @Test
    void testGetMinOccurrences() {
        assertEquals(5, PatternQuantifier.exactly(5).getMinOccurrences());
        assertEquals(1, PatternQuantifier.oneOrMore().getMinOccurrences());
        assertEquals(0, PatternQuantifier.zeroOrMore().getMinOccurrences());
        assertEquals(0, PatternQuantifier.optional().getMinOccurrences());
        assertEquals(2, PatternQuantifier.times(2, 5).getMinOccurrences());
        assertEquals(3, PatternQuantifier.atLeast(3).getMinOccurrences());
    }

    @Test
    void testGetMaxOccurrences() {
        assertEquals(5, PatternQuantifier.exactly(5).getMaxOccurrences());
        assertEquals(Integer.MAX_VALUE, PatternQuantifier.oneOrMore().getMaxOccurrences());
        assertEquals(Integer.MAX_VALUE, PatternQuantifier.zeroOrMore().getMaxOccurrences());
        assertEquals(1, PatternQuantifier.optional().getMaxOccurrences());
        assertEquals(5, PatternQuantifier.times(2, 5).getMaxOccurrences());
        assertEquals(Integer.MAX_VALUE, PatternQuantifier.atLeast(3).getMaxOccurrences());
    }
}
