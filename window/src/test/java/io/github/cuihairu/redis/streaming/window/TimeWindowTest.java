package io.github.cuihairu.redis.streaming.window;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TimeWindow
 */
class TimeWindowTest {

    @Test
    void testConstructorAndGetters() {
        TimeWindow window = new TimeWindow(1000L, 2000L);

        assertEquals(1000L, window.getStart());
        assertEquals(2000L, window.getEnd());
    }

    @Test
    void testMaxTimestamp() {
        TimeWindow window = new TimeWindow(1000L, 2000L);

        assertEquals(1999L, window.maxTimestamp());
    }

    @Test
    void testContains() {
        TimeWindow window = new TimeWindow(1000L, 2000L);

        assertTrue(window.contains(1000L));
        assertTrue(window.contains(1500L));
        assertTrue(window.contains(1999L));

        assertFalse(window.contains(999L));
        assertFalse(window.contains(2000L));
        assertFalse(window.contains(2001L));
    }

    @Test
    void testGetSize() {
        TimeWindow window = new TimeWindow(1000L, 2000L);

        assertEquals(1000L, window.getSize());
    }

    @Test
    void testEquals() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(1000L, 2000L);
        TimeWindow w3 = new TimeWindow(1000L, 3000L);
        TimeWindow w4 = new TimeWindow(2000L, 3000L);

        assertEquals(w1, w2);
        assertNotEquals(w1, w3);
        assertNotEquals(w1, w4);
        assertNotEquals(w1, null);
        assertNotEquals(w1, "not a window");
    }

    @Test
    void testHashCode() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(1000L, 2000L);
        TimeWindow w3 = new TimeWindow(1000L, 3000L);

        assertEquals(w1.hashCode(), w2.hashCode());
        assertNotEquals(w1.hashCode(), w3.hashCode());
    }

    @Test
    void testToString() {
        TimeWindow window = new TimeWindow(1000L, 2000L);

        String str = window.toString();
        assertTrue(str.contains("1000"));
        assertTrue(str.contains("2000"));
    }

    @Test
    void testMerge() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(1500L, 2500L);

        TimeWindow merged = TimeWindow.merge(w1, w2);

        assertEquals(1000L, merged.getStart());
        assertEquals(2500L, merged.getEnd());
    }

    @Test
    void testMergeNonOverlapping() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(3000L, 4000L);

        TimeWindow merged = TimeWindow.merge(w1, w2);

        assertEquals(1000L, merged.getStart());
        assertEquals(4000L, merged.getEnd());
    }

    @Test
    void testMergeAdjacent() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(2000L, 3000L);

        TimeWindow merged = TimeWindow.merge(w1, w2);

        assertEquals(1000L, merged.getStart());
        assertEquals(3000L, merged.getEnd());
    }

    @Test
    void testMergeSameWindow() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(1000L, 2000L);

        TimeWindow merged = TimeWindow.merge(w1, w2);

        assertEquals(1000L, merged.getStart());
        assertEquals(2000L, merged.getEnd());
    }

    @Test
    void testIntersects() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(1500L, 2500L);
        TimeWindow w3 = new TimeWindow(2000L, 3000L);
        TimeWindow w4 = new TimeWindow(3000L, 4000L);

        assertTrue(TimeWindow.intersects(w1, w2));
        assertFalse(TimeWindow.intersects(w1, w3)); // adjacent is not intersecting
        assertFalse(TimeWindow.intersects(w1, w4));
    }

    @Test
    void testIntersWithin() {
        TimeWindow w1 = new TimeWindow(1000L, 3000L);
        TimeWindow w2 = new TimeWindow(1500L, 2000L);

        assertTrue(TimeWindow.intersects(w1, w2));
        assertTrue(TimeWindow.intersects(w2, w1));
    }

    @Test
    void testZeroLengthWindow() {
        TimeWindow window = new TimeWindow(1000L, 1000L);

        assertEquals(1000L, window.getStart());
        assertEquals(1000L, window.getEnd());
        assertEquals(0L, window.getSize());
        assertEquals(999L, window.maxTimestamp());
        assertFalse(window.contains(1000L));
    }

    @Test
    void testNegativeTimestamps() {
        TimeWindow window = new TimeWindow(-1000L, 1000L);

        assertEquals(-1000L, window.getStart());
        assertEquals(1000L, window.getEnd());
        assertEquals(2000L, window.getSize());
        assertTrue(window.contains(0L));
        assertTrue(window.contains(-500L));
        assertTrue(window.contains(500L));
    }

    @Test
    void testVeryLargeTimestamps() {
        long start = Long.MAX_VALUE - 1000L;
        long end = Long.MAX_VALUE;
        TimeWindow window = new TimeWindow(start, end);

        assertEquals(start, window.getStart());
        assertEquals(end, window.getEnd());
        assertEquals(1000L, window.getSize());
        assertEquals(Long.MAX_VALUE - 1, window.maxTimestamp());
    }

    @Test
    void testWindowWithGap() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(3000L, 4000L);

        TimeWindow merged = TimeWindow.merge(w1, w2);

        // The merge still combines the range
        assertEquals(1000L, merged.getStart());
        assertEquals(4000L, merged.getEnd());
        assertEquals(3000L, merged.getSize());
    }

    @Test
    void testReverseMerge() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(500L, 1500L);

        TimeWindow merged1 = TimeWindow.merge(w1, w2);
        TimeWindow merged2 = TimeWindow.merge(w2, w1);

        assertEquals(merged1.getStart(), merged2.getStart());
        assertEquals(merged1.getEnd(), merged2.getEnd());
    }

    @Test
    void testContainsEdgeCases() {
        TimeWindow window = new TimeWindow(1000L, 2000L);

        // Exactly at start
        assertTrue(window.contains(1000L));

        // Exactly at end (should be false because end is exclusive)
        assertFalse(window.contains(2000L));

        // One millisecond before start
        assertFalse(window.contains(999L));

        // One millisecond before end
        assertTrue(window.contains(1999L));
    }

    @Test
    void testSelfIntersect() {
        TimeWindow window = new TimeWindow(1000L, 2000L);

        assertTrue(TimeWindow.intersects(window, window));
    }
}
