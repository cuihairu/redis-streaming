package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JoinType enum
 */
class JoinTypeTest {

    // ===== Enum Values Tests =====

    @Test
    void testInnerEnumValueExists() {
        assertEquals(JoinType.INNER, JoinType.valueOf("INNER"));
    }

    @Test
    void testLeftEnumValueExists() {
        assertEquals(JoinType.LEFT, JoinType.valueOf("LEFT"));
    }

    @Test
    void testRightEnumValueExists() {
        assertEquals(JoinType.RIGHT, JoinType.valueOf("RIGHT"));
    }

    @Test
    void testFullOuterEnumValueExists() {
        assertEquals(JoinType.FULL_OUTER, JoinType.valueOf("FULL_OUTER"));
    }

    // ===== Enum Constants Tests =====

    @Test
    void testAllEnumValues() {
        JoinType[] values = JoinType.values();

        assertEquals(4, values.length);
        assertEquals(JoinType.INNER, values[0]);
        assertEquals(JoinType.LEFT, values[1]);
        assertEquals(JoinType.RIGHT, values[2]);
        assertEquals(JoinType.FULL_OUTER, values[3]);
    }

    // ===== Enum Serialization Tests =====

    @Test
    void testEnumImplementsSerializable() {
        // JoinType implements Serializable, so it should be serializable
        assertTrue(JoinType.INNER instanceof java.io.Serializable);
        assertTrue(JoinType.LEFT instanceof java.io.Serializable);
        assertTrue(JoinType.RIGHT instanceof java.io.Serializable);
        assertTrue(JoinType.FULL_OUTER instanceof java.io.Serializable);
    }

    // ===== valueOf Tests =====

    @Test
    void testValueOfWithInvalidName() {
        assertThrows(IllegalArgumentException.class, () -> JoinType.valueOf("INVALID"));
    }

    @Test
    void testValueOfWithCaseSensitive() {
        assertEquals(JoinType.INNER, JoinType.valueOf("INNER"));
        assertThrows(IllegalArgumentException.class, () -> JoinType.valueOf("inner"));
        assertThrows(IllegalArgumentException.class, () -> JoinType.valueOf("Inner"));
    }

    // ===== name Tests =====

    @Test
    void testInnerName() {
        assertEquals("INNER", JoinType.INNER.name());
    }

    @Test
    void testLeftName() {
        assertEquals("LEFT", JoinType.LEFT.name());
    }

    @Test
    void testRightName() {
        assertEquals("RIGHT", JoinType.RIGHT.name());
    }

    @Test
    void testFullOuterName() {
        assertEquals("FULL_OUTER", JoinType.FULL_OUTER.name());
    }

    // ===== ordinal Tests =====

    @Test
    void testInnerOrdinal() {
        assertEquals(0, JoinType.INNER.ordinal());
    }

    @Test
    void testLeftOrdinal() {
        assertEquals(1, JoinType.LEFT.ordinal());
    }

    @Test
    void testRightOrdinal() {
        assertEquals(2, JoinType.RIGHT.ordinal());
    }

    @Test
    void testFullOuterOrdinal() {
        assertEquals(3, JoinType.FULL_OUTER.ordinal());
    }

    // ===== Enum Equality Tests =====

    @Test
    void testEnumEquality() {
        assertEquals(JoinType.INNER, JoinType.INNER);
        assertEquals(JoinType.LEFT, JoinType.LEFT);
        assertEquals(JoinType.RIGHT, JoinType.RIGHT);
        assertEquals(JoinType.FULL_OUTER, JoinType.FULL_OUTER);
    }

    @Test
    void testEnumInequality() {
        assertNotEquals(JoinType.INNER, JoinType.LEFT);
        assertNotEquals(JoinType.LEFT, JoinType.RIGHT);
        assertNotEquals(JoinType.RIGHT, JoinType.FULL_OUTER);
        assertNotEquals(JoinType.FULL_OUTER, JoinType.INNER);
    }

    // ===== Semantic Meaning Tests =====

    @Test
    void testInnerJoinMeaning() {
        // Inner join: Only emit when both streams have matching keys
        JoinType type = JoinType.INNER;
        assertEquals("INNER", type.name());
    }

    @Test
    void testLeftJoinMeaning() {
        // Left join: Emit for all left stream elements, right may be null
        JoinType type = JoinType.LEFT;
        assertEquals("LEFT", type.name());
    }

    @Test
    void testRightJoinMeaning() {
        // Right join: Emit for all right stream elements, left may be null
        JoinType type = JoinType.RIGHT;
        assertEquals("RIGHT", type.name());
    }

    @Test
    void testFullOuterJoinMeaning() {
        // Full outer join: Emit for all elements from both streams
        JoinType type = JoinType.FULL_OUTER;
        assertEquals("FULL_OUTER", type.name());
    }
}
