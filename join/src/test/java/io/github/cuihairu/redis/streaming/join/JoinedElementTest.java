package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JoinedElementTest {

    @Test
    void testBothPresent() {
        JoinedElement<String, Integer> joined = JoinedElement.of("left", 42, 1000);

        assertTrue(joined.isBothPresent());
        assertFalse(joined.isLeftOnly());
        assertFalse(joined.isRightOnly());
        assertEquals("left", joined.getLeft());
        assertEquals(42, joined.getRight());
        assertEquals(1000, joined.getTimestamp());
    }

    @Test
    void testLeftOnly() {
        JoinedElement<String, Integer> joined = JoinedElement.leftOnly("left", 1000);

        assertTrue(joined.isLeftOnly());
        assertFalse(joined.isBothPresent());
        assertFalse(joined.isRightOnly());
        assertEquals("left", joined.getLeft());
        assertNull(joined.getRight());
    }

    @Test
    void testRightOnly() {
        JoinedElement<String, Integer> joined = JoinedElement.rightOnly(42, 1000);

        assertTrue(joined.isRightOnly());
        assertFalse(joined.isBothPresent());
        assertFalse(joined.isLeftOnly());
        assertNull(joined.getLeft());
        assertEquals(42, joined.getRight());
    }

    @Test
    void testBothNull() {
        JoinedElement<String, Integer> joined = JoinedElement.of(null, null, 1000);

        assertFalse(joined.isBothPresent());
        assertFalse(joined.isLeftOnly());
        assertFalse(joined.isRightOnly());
    }
}
