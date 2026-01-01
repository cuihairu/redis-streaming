package io.github.cuihairu.redis.streaming.mq.admin.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PendingSort
 */
class PendingSortTest {

    @Test
    void testEnumValues() {
        assertEquals(3, PendingSort.values().length);

        PendingSort[] sorts = PendingSort.values();
        assertTrue(java.util.Arrays.asList(sorts).contains(PendingSort.IDLE));
        assertTrue(java.util.Arrays.asList(sorts).contains(PendingSort.DELIVERIES));
        assertTrue(java.util.Arrays.asList(sorts).contains(PendingSort.ID));
    }

    @Test
    void testValueOf() {
        assertEquals(PendingSort.IDLE, PendingSort.valueOf("IDLE"));
        assertEquals(PendingSort.DELIVERIES, PendingSort.valueOf("DELIVERIES"));
        assertEquals(PendingSort.ID, PendingSort.valueOf("ID"));
    }

    @Test
    void testEnumName() {
        assertEquals("IDLE", PendingSort.IDLE.name());
        assertEquals("DELIVERIES", PendingSort.DELIVERIES.name());
        assertEquals("ID", PendingSort.ID.name());
    }

    @Test
    void testEnumOrdinal() {
        assertEquals(0, PendingSort.IDLE.ordinal());
        assertEquals(1, PendingSort.DELIVERIES.ordinal());
        assertEquals(2, PendingSort.ID.ordinal());
    }

    @Test
    void testEnumEquality() {
        assertEquals(PendingSort.IDLE, PendingSort.IDLE);
        assertEquals(PendingSort.DELIVERIES, PendingSort.DELIVERIES);

        assertNotEquals(PendingSort.IDLE, PendingSort.DELIVERIES);
        assertNotEquals(PendingSort.ID, PendingSort.IDLE);
    }

    @Test
    void testEnumToString() {
        assertEquals("IDLE", PendingSort.IDLE.toString());
        assertEquals("DELIVERIES", PendingSort.DELIVERIES.toString());
        assertEquals("ID", PendingSort.ID.toString());
    }

    @Test
    void testValueOfWithInvalidString() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PendingSort.valueOf("INVALID_SORT"));

        assertTrue(exception.getMessage().contains("No enum constant"));
    }

    @Test
    void testValueOfWithNull() {
        assertThrows(NullPointerException.class,
                () -> PendingSort.valueOf(null));
    }

    @Test
    void testEnumComparability() {
        // Enums implement Comparable
        assertTrue(PendingSort.IDLE.compareTo(PendingSort.DELIVERIES) < 0);
        assertTrue(PendingSort.DELIVERIES.compareTo(PendingSort.ID) < 0);
        assertEquals(0, PendingSort.IDLE.compareTo(PendingSort.IDLE));
        assertTrue(PendingSort.ID.compareTo(PendingSort.IDLE) > 0);
    }

    @Test
    void testEnumClass() {
        assertEquals(PendingSort.class, PendingSort.IDLE.getClass());
        assertEquals(PendingSort.class, PendingSort.DELIVERIES.getClass());
    }

    @Test
    void testGetDeclaringClass() {
        assertEquals(PendingSort.class, PendingSort.IDLE.getDeclaringClass());
        assertEquals(PendingSort.class, PendingSort.DELIVERIES.getDeclaringClass());
    }

    @Test
    void testEnumConstants() {
        assertNotNull(PendingSort.IDLE);
        assertNotNull(PendingSort.DELIVERIES);
        assertNotNull(PendingSort.ID);
    }

    @Test
    void testHashCode() {
        assertEquals(PendingSort.IDLE.hashCode(), PendingSort.IDLE.hashCode());
        assertNotEquals(PendingSort.IDLE.hashCode(), PendingSort.DELIVERIES.hashCode());
    }

    @Test
    void testEnumOrdering() {
        // Verify the ordering matches declaration order
        PendingSort[] values = PendingSort.values();
        assertEquals(PendingSort.IDLE, values[0]);
        assertEquals(PendingSort.DELIVERIES, values[1]);
        assertEquals(PendingSort.ID, values[2]);
    }
}
