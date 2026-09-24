package io.github.cuihairu.redis.streaming.window;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers all {@code TimeWindow#equals} branches (identity, null, type, field equality). */
class TimeWindowEqualsCoverageTest {

    @Test
    void equalsCoversAllBranches() {
        TimeWindow w = new TimeWindow(10, 20);
        TimeWindow same = new TimeWindow(10, 20);
        TimeWindow different = new TimeWindow(10, 30);

        assertTrue(w.equals(w), "identity");
        assertFalse(w.equals(null), "null");
        assertFalse(w.equals("10-20"), "different type");
        assertTrue(w.equals(same), "field equality");
        assertFalse(w.equals(different), "field inequality");
        assertEquals(w.hashCode(), same.hashCode());
        assertNotEquals(w, different);
    }
}
