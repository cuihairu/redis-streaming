package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CollectionCost enum
 */
class CollectionCostTest {

    @Test
    void testCollectionCostValues() {
        CollectionCost[] values = CollectionCost.values();

        assertEquals(3, values.length);
        assertEquals(CollectionCost.LOW, values[0]);
        assertEquals(CollectionCost.MEDIUM, values[1]);
        assertEquals(CollectionCost.HIGH, values[2]);
    }

    @Test
    void testCollectionCostValueOf() {
        assertEquals(CollectionCost.LOW, CollectionCost.valueOf("LOW"));
        assertEquals(CollectionCost.MEDIUM, CollectionCost.valueOf("MEDIUM"));
        assertEquals(CollectionCost.HIGH, CollectionCost.valueOf("HIGH"));
    }

    @Test
    void testCollectionCostValueOfInvalidString() {
        assertThrows(IllegalArgumentException.class, () -> CollectionCost.valueOf("INVALID"));
    }

    @Test
    void testCollectionCostValueOfNull() {
        assertThrows(NullPointerException.class, () -> CollectionCost.valueOf(null));
    }

    @Test
    void testCollectionCostValuesAsList() {
        List<CollectionCost> values = List.of(CollectionCost.values());

        assertEquals(3, values.size());
        assertTrue(values.contains(CollectionCost.LOW));
        assertTrue(values.contains(CollectionCost.MEDIUM));
        assertTrue(values.contains(CollectionCost.HIGH));
    }

    @Test
    void testCollectionCostOrder() {
        CollectionCost[] values = CollectionCost.values();

        // Verify declaration order
        assertEquals(0, CollectionCost.LOW.ordinal());
        assertEquals(1, CollectionCost.MEDIUM.ordinal());
        assertEquals(2, CollectionCost.HIGH.ordinal());
    }

    @Test
    void testCollectionCostName() {
        assertEquals("LOW", CollectionCost.LOW.name());
        assertEquals("MEDIUM", CollectionCost.MEDIUM.name());
        assertEquals("HIGH", CollectionCost.HIGH.name());
    }

    @Test
    void testCollectionCostToString() {
        assertEquals("LOW", CollectionCost.LOW.toString());
        assertEquals("MEDIUM", CollectionCost.MEDIUM.toString());
        assertEquals("HIGH", CollectionCost.HIGH.toString());
    }

    @Test
    void testCollectionCostEquals() {
        assertEquals(CollectionCost.LOW, CollectionCost.LOW);
        assertEquals(CollectionCost.MEDIUM, CollectionCost.MEDIUM);
        assertEquals(CollectionCost.HIGH, CollectionCost.HIGH);

        assertNotEquals(CollectionCost.LOW, CollectionCost.MEDIUM);
        assertNotEquals(CollectionCost.LOW, CollectionCost.HIGH);
        assertNotEquals(CollectionCost.MEDIUM, CollectionCost.HIGH);
    }

    @Test
    void testCollectionCostHashCode() {
        assertEquals(CollectionCost.LOW.hashCode(), CollectionCost.LOW.hashCode());
        assertNotEquals(CollectionCost.LOW.hashCode(), CollectionCost.MEDIUM.hashCode());
    }

    @Test
    void testCollectionCostCompareTo() {
        assertTrue(CollectionCost.LOW.compareTo(CollectionCost.MEDIUM) < 0);
        assertTrue(CollectionCost.LOW.compareTo(CollectionCost.HIGH) < 0);
        assertTrue(CollectionCost.MEDIUM.compareTo(CollectionCost.HIGH) < 0);

        assertTrue(CollectionCost.MEDIUM.compareTo(CollectionCost.LOW) > 0);
        assertTrue(CollectionCost.HIGH.compareTo(CollectionCost.LOW) > 0);
        assertTrue(CollectionCost.HIGH.compareTo(CollectionCost.MEDIUM) > 0);

        assertEquals(0, CollectionCost.LOW.compareTo(CollectionCost.LOW));
    }

    @Test
    void testCollectionCostInSwitch() {
        CollectionCost cost = CollectionCost.MEDIUM;
        int result = 0;

        switch (cost) {
            case LOW:
                result = 1;
                break;
            case MEDIUM:
                result = 2;
                break;
            case HIGH:
                result = 3;
                break;
        }

        assertEquals(2, result);
    }
}
