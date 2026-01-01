package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChangeThresholdType
 */
class ChangeThresholdTypeTest {

    @Test
    void testGetValue() {
        assertEquals("percentage", ChangeThresholdType.PERCENTAGE.getValue());
        assertEquals("absolute", ChangeThresholdType.ABSOLUTE.getValue());
        assertEquals("any", ChangeThresholdType.ANY.getValue());
    }

    @Test
    void testToString() {
        assertEquals("percentage", ChangeThresholdType.PERCENTAGE.toString());
        assertEquals("absolute", ChangeThresholdType.ABSOLUTE.toString());
        assertEquals("any", ChangeThresholdType.ANY.toString());
    }

    @Test
    void testFromValue() {
        assertEquals(ChangeThresholdType.PERCENTAGE, ChangeThresholdType.fromValue("percentage"));
        assertEquals(ChangeThresholdType.ABSOLUTE, ChangeThresholdType.fromValue("absolute"));
        assertEquals(ChangeThresholdType.ANY, ChangeThresholdType.fromValue("any"));
    }

    @Test
    void testFromValueWithInvalidValue() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ChangeThresholdType.fromValue("invalid"));

        assertTrue(exception.getMessage().contains("Unknown threshold type"));
        assertTrue(exception.getMessage().contains("invalid"));
    }

    @Test
    void testFromValueWithNull() {
        assertThrows(IllegalArgumentException.class,
                () -> ChangeThresholdType.fromValue(null));
    }

    @Test
    void testEnumValues() {
        assertEquals(3, ChangeThresholdType.values().length);

        ChangeThresholdType[] types = ChangeThresholdType.values();
        assertTrue(java.util.Arrays.asList(types).contains(ChangeThresholdType.PERCENTAGE));
        assertTrue(java.util.Arrays.asList(types).contains(ChangeThresholdType.ABSOLUTE));
        assertTrue(java.util.Arrays.asList(types).contains(ChangeThresholdType.ANY));
    }

    @Test
    void testValueOf() {
        assertEquals(ChangeThresholdType.PERCENTAGE, ChangeThresholdType.valueOf("PERCENTAGE"));
        assertEquals(ChangeThresholdType.ABSOLUTE, ChangeThresholdType.valueOf("ABSOLUTE"));
        assertEquals(ChangeThresholdType.ANY, ChangeThresholdType.valueOf("ANY"));
    }

    @Test
    void testEnumName() {
        assertEquals("PERCENTAGE", ChangeThresholdType.PERCENTAGE.name());
        assertEquals("ABSOLUTE", ChangeThresholdType.ABSOLUTE.name());
        assertEquals("ANY", ChangeThresholdType.ANY.name());
    }

    @Test
    void testEnumOrdinal() {
        assertEquals(0, ChangeThresholdType.PERCENTAGE.ordinal());
        assertEquals(1, ChangeThresholdType.ABSOLUTE.ordinal());
        assertEquals(2, ChangeThresholdType.ANY.ordinal());
    }

    @Test
    void testEnumEquality() {
        assertEquals(ChangeThresholdType.PERCENTAGE, ChangeThresholdType.PERCENTAGE);
        assertEquals(ChangeThresholdType.ABSOLUTE, ChangeThresholdType.ABSOLUTE);

        assertNotEquals(ChangeThresholdType.PERCENTAGE, ChangeThresholdType.ABSOLUTE);
        assertNotEquals(ChangeThresholdType.ANY, ChangeThresholdType.PERCENTAGE);
    }

    @Test
    void testEnumHashCode() {
        assertEquals(ChangeThresholdType.PERCENTAGE.hashCode(), ChangeThresholdType.PERCENTAGE.hashCode());
        assertNotEquals(ChangeThresholdType.PERCENTAGE.hashCode(), ChangeThresholdType.ABSOLUTE.hashCode());
    }

    @Test
    void testValueOfWithInvalidString() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ChangeThresholdType.valueOf("INVALID_TYPE"));

        assertTrue(exception.getMessage().contains("No enum constant"));
    }

    @Test
    void testValueOfWithNull() {
        assertThrows(NullPointerException.class,
                () -> ChangeThresholdType.valueOf(null));
    }

    @Test
    void testEnumComparability() {
        assertTrue(ChangeThresholdType.PERCENTAGE.compareTo(ChangeThresholdType.ABSOLUTE) < 0);
        assertTrue(ChangeThresholdType.ABSOLUTE.compareTo(ChangeThresholdType.ANY) < 0);
        assertEquals(0, ChangeThresholdType.PERCENTAGE.compareTo(ChangeThresholdType.PERCENTAGE));
        assertTrue(ChangeThresholdType.ANY.compareTo(ChangeThresholdType.PERCENTAGE) > 0);
    }

    @Test
    void testEnumClass() {
        assertEquals(ChangeThresholdType.class, ChangeThresholdType.PERCENTAGE.getClass());
        assertEquals(ChangeThresholdType.class, ChangeThresholdType.ABSOLUTE.getClass());
    }

    @Test
    void testGetDeclaringClass() {
        assertEquals(ChangeThresholdType.class, ChangeThresholdType.PERCENTAGE.getDeclaringClass());
        assertEquals(ChangeThresholdType.class, ChangeThresholdType.ABSOLUTE.getDeclaringClass());
    }

    @Test
    void testAllValuesAreUnique() {
        java.util.Set<String> values = new java.util.HashSet<>();
        for (ChangeThresholdType type : ChangeThresholdType.values()) {
            assertTrue(values.add(type.getValue()),
                    "Duplicate value: " + type.getValue());
        }
        assertEquals(ChangeThresholdType.values().length, values.size());
    }
}
