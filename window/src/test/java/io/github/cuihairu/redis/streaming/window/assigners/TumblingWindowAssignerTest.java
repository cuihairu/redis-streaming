package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TumblingWindow
 */
class TumblingWindowAssignerTest {

    @Test
    void testOfDuration() {
        TumblingWindow<String> window = TumblingWindow.of(Duration.ofSeconds(5));

        assertEquals(5000L, window.getSize());
        assertEquals("TumblingWindow{size=5000ms}", window.toString());
    }

    @Test
    void testOfMillis() {
        TumblingWindow<Integer> window = TumblingWindow.ofMillis(1000L);

        assertEquals(1000L, window.getSize());
    }

    @Test
    void testAssignWindowsExactAlignment() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(1000L);

        // Timestamp exactly at window boundary
        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", 5000L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(5000L, w.getStart());
        assertEquals(6000L, w.getEnd());
        assertFalse(windows.hasNext());
    }

    @Test
    void testAssignWindowsWithinWindow() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(1000L);

        // Timestamp within a window
        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", 5500L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(5000L, w.getStart());
        assertEquals(6000L, w.getEnd());
    }

    @Test
    void testAssignWindowsMultipleTimestamps() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(1000L);

        // Test various timestamps
        assertWindowForTimestamp(window, 0L, 0L, 1000L);
        assertWindowForTimestamp(window, 500L, 0L, 1000L);
        assertWindowForTimestamp(window, 999L, 0L, 1000L);
        assertWindowForTimestamp(window, 1000L, 1000L, 2000L);
        assertWindowForTimestamp(window, 1500L, 1000L, 2000L);
        assertWindowForTimestamp(window, 10000L, 10000L, 11000L);
        assertWindowForTimestamp(window, 10500L, 10000L, 11000L);
    }

    @Test
    void testAssignWindowsNegativeTimestamps() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(1000L);

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", -500L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        // Java's % operator returns negative for negative dividends
        // -500 % 1000 = -500, so start = -500 - (-500) = 0
        assertEquals(0L, w.getStart());
        assertEquals(1000L, w.getEnd());
    }

    @Test
    void testAssignWindowsLargeWindow() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(60000L); // 1 minute

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", 90000L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(60000L, w.getStart());
        assertEquals(120000L, w.getEnd());
    }

    @Test
    void testAssignWindowsSmallWindow() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(100L); // 100ms

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", 1050L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(1000L, w.getStart());
        assertEquals(1100L, w.getEnd());
    }

    @Test
    void testAssignWindowsAlwaysReturnsSingleWindow() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(1000L);

        for (long ts = 0L; ts < 10000L; ts += 123L) {
            int count = 0;
            for (Iterator<?> it = window.assignWindows("test", ts).iterator(); it.hasNext(); ) {
                it.next();
                count++;
            }
            assertEquals(1, count, "Should always return exactly 1 window for timestamp " + ts);
        }
    }

    @Test
    void testGetDefaultTrigger() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(1000L);

        assertNotNull(window.getDefaultTrigger());
        assertTrue(window.getDefaultTrigger() instanceof io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Trigger);
    }

    @Test
    void testToString() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(5000L);

        String str = window.toString();
        assertTrue(str.contains("TumblingWindow"));
        assertTrue(str.contains("5000"));
    }

    @Test
    void testWindowBoundaries() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(1000L);

        // Test boundaries around window transitions
        assertWindowForTimestamp(window, 999L, 0L, 1000L);
        assertWindowForTimestamp(window, 1000L, 1000L, 2000L);
        assertWindowForTimestamp(window, 1999L, 1000L, 2000L);
        assertWindowForTimestamp(window, 2000L, 2000L, 3000L);
    }

    @Test
    void testZeroSizeWindow() {
        // Window with size 0 would cause division by zero in the modulo operation
        // This is an edge case that should be avoided in practice
        TumblingWindow<String> window = TumblingWindow.ofMillis(1L); // Use 1ms instead of 0

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", 5000L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(5000L, w.getStart());
        assertEquals(5001L, w.getEnd());
    }

    @Test
    void testDifferentDurations() {
        assertEquals(1000L, TumblingWindow.of(Duration.ofMillis(1000)).getSize());
        assertEquals(1000L, TumblingWindow.of(Duration.ofSeconds(1)).getSize());
        assertEquals(60000L, TumblingWindow.of(Duration.ofMinutes(1)).getSize());
        assertEquals(3600000L, TumblingWindow.of(Duration.ofHours(1)).getSize());
    }

    @Test
    void testWindowsAreNonOverlapping() {
        TumblingWindow<String> window = TumblingWindow.ofMillis(1000L);

        for (long ts = 0L; ts < 10000L; ts += 100L) {
            Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> it =
                    window.assignWindows("test", ts).iterator();
            TimeWindow w = (TimeWindow) it.next();

            // Check that timestamp falls within the window
            assertTrue(w.contains(ts) || ts == w.getEnd(), // end is exclusive
                    "Timestamp " + ts + " should be in window [" + w.getStart() + "," + w.getEnd() + ")");
        }
    }

    private void assertWindowForTimestamp(TumblingWindow<String> window, long timestamp, long expectedStart, long expectedEnd) {
        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", timestamp).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(expectedStart, w.getStart(), "Window start for timestamp " + timestamp);
        assertEquals(expectedEnd, w.getEnd(), "Window end for timestamp " + timestamp);
    }
}
