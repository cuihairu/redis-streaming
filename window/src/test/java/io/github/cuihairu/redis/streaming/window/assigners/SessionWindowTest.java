package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SessionWindow
 */
class SessionWindowTest {

    @Test
    void testWithGap() {
        // Given
        Duration gap = Duration.ofSeconds(30);

        // When
        SessionWindow<String> sessionWindow = SessionWindow.withGap(gap);

        // Then
        assertNotNull(sessionWindow);
        assertEquals(30000, sessionWindow.getSessionGap());
    }

    @Test
    void testWithGapMillis() {
        // Given
        long gapMillis = 5000;

        // When
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(gapMillis);

        // Then
        assertNotNull(sessionWindow);
        assertEquals(5000, sessionWindow.getSessionGap());
    }

    @Test
    void testAssignWindows() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(1000);
        String element = "test-element";
        long timestamp = 10000;

        // When
        Iterable<WindowAssigner.Window> windows = sessionWindow.assignWindows(element, timestamp);
        Iterator<WindowAssigner.Window> iterator = windows.iterator();

        // Then
        assertTrue(iterator.hasNext());
        WindowAssigner.Window window = iterator.next();
        assertEquals(10000, window.getStart());
        assertEquals(11000, window.getEnd());
        assertFalse(iterator.hasNext());
    }

    @Test
    void testAssignWindowsWithZeroGap() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(0);
        String element = "test";
        long timestamp = 5000;

        // When
        Iterable<WindowAssigner.Window> windows = sessionWindow.assignWindows(element, timestamp);
        WindowAssigner.Window window = windows.iterator().next();

        // Then
        assertEquals(5000, window.getStart());
        assertEquals(5000, window.getEnd());
    }

    @Test
    void testAssignWindowsWithLargeGap() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(60000); // 1 minute
        String element = "test";
        long timestamp = 0;

        // When
        Iterable<WindowAssigner.Window> windows = sessionWindow.assignWindows(element, timestamp);
        WindowAssigner.Window window = windows.iterator().next();

        // Then
        assertEquals(0, window.getStart());
        assertEquals(60000, window.getEnd());
    }

    @Test
    void testGetDefaultTrigger() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(1000);

        // When
        WindowAssigner.Trigger<String> trigger = sessionWindow.getDefaultTrigger();

        // Then
        assertNotNull(trigger);
    }

    @Test
    void testGetSessionGap() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(5000);

        // When
        long gap = sessionWindow.getSessionGap();

        // Then
        assertEquals(5000, gap);
    }

    @Test
    void testToString() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(10000);

        // When
        String str = sessionWindow.toString();

        // Then
        assertNotNull(str);
        assertTrue(str.contains("SessionWindow"));
        assertTrue(str.contains("10000"));
        assertTrue(str.contains("ms"));
    }

    @Test
    void testShouldMergeWithIntersectingWindows() {
        // Given - two windows that overlap
        TimeWindow w1 = new TimeWindow(0, 1000);
        TimeWindow w2 = new TimeWindow(500, 1500);

        // When
        boolean shouldMerge = SessionWindow.shouldMerge(w1, w2);

        // Then
        assertTrue(shouldMerge, "Overlapping windows should be merged");
    }

    @Test
    void testShouldMergeWithAdjacentWindows() {
        // Given - two windows that are adjacent (end == start)
        // Note: TimeWindow.intersects uses < not <=, so adjacent windows DON'T intersect
        TimeWindow w1 = new TimeWindow(0, 1000);
        TimeWindow w2 = new TimeWindow(1000, 2000);

        // When
        boolean shouldMerge = SessionWindow.shouldMerge(w1, w2);

        // Then - adjacent windows (end == start) do NOT intersect in TimeWindow.intersects
        // This is the actual behavior of the TimeWindow.intersects method
        assertFalse(shouldMerge, "Adjacent windows do NOT intersect (end < start check)");
    }

    @Test
    void testShouldMergeWithNonIntersectingWindows() {
        // Given - two windows that don't overlap
        TimeWindow w1 = new TimeWindow(0, 1000);
        TimeWindow w2 = new TimeWindow(2000, 3000);

        // When
        boolean shouldMerge = SessionWindow.shouldMerge(w1, w2);

        // Then
        assertFalse(shouldMerge, "Non-intersecting windows should not be merged");
    }

    @Test
    void testShouldMergeWithContainedWindow() {
        // Given - one window contained within another
        TimeWindow w1 = new TimeWindow(0, 5000);
        TimeWindow w2 = new TimeWindow(1000, 2000);

        // When
        boolean shouldMerge = SessionWindow.shouldMerge(w1, w2);

        // Then
        assertTrue(shouldMerge, "Contained window should be merged");
    }

    @Test
    void testAssignWindowsCreatesSessionWindowFromTimestamp() {
        // Given
        SessionWindow<Integer> sessionWindow = SessionWindow.withGapMillis(5000);
        Integer element = 42;
        long timestamp = 12345;

        // When
        Iterable<WindowAssigner.Window> windows = sessionWindow.assignWindows(element, timestamp);
        WindowAssigner.Window window = windows.iterator().next();

        // Then - session window starts at timestamp and extends by gap
        assertEquals(12345, window.getStart());
        assertEquals(17345, window.getEnd());
    }

    @Test
    void testAssignWindowsWithNegativeTimestamp() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(1000);
        String element = "test";
        long timestamp = -1000;

        // When
        Iterable<WindowAssigner.Window> windows = sessionWindow.assignWindows(element, timestamp);
        WindowAssigner.Window window = windows.iterator().next();

        // Then
        assertEquals(-1000, window.getStart());
        assertEquals(0, window.getEnd());
    }

    @Test
    void testMultipleAssignmentsForSameElement() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(1000);
        String element = "test";
        long timestamp = 10000;

        // When - assign same element multiple times
        Iterable<WindowAssigner.Window> windows1 = sessionWindow.assignWindows(element, timestamp);
        Iterable<WindowAssigner.Window> windows2 = sessionWindow.assignWindows(element, timestamp);

        // Then - should create independent windows (session merging happens at runtime)
        WindowAssigner.Window w1 = windows1.iterator().next();
        WindowAssigner.Window w2 = windows2.iterator().next();
        assertEquals(w1.getStart(), w2.getStart());
        assertEquals(w1.getEnd(), w2.getEnd());
    }

    @Test
    void testSessionGapInSeconds() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGap(Duration.ofSeconds(10));

        // When
        long gap = sessionWindow.getSessionGap();

        // Then
        assertEquals(10000, gap);
    }

    @Test
    void testSessionGapInMinutes() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGap(Duration.ofMinutes(5));

        // When
        long gap = sessionWindow.getSessionGap();

        // Then
        assertEquals(300000, gap);
    }

    @Test
    void testSessionGapInHours() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGap(Duration.ofHours(1));

        // When
        long gap = sessionWindow.getSessionGap();

        // Then
        assertEquals(3600000, gap);
    }

    @Test
    void testWindowContainsTimestamp() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(1000);
        String element = "test";
        long timestamp = 5000;

        // When
        Iterable<WindowAssigner.Window> windows = sessionWindow.assignWindows(element, timestamp);
        TimeWindow timeWindow = (TimeWindow) windows.iterator().next();

        // Then - window should contain the timestamp that created it
        assertTrue(timeWindow.contains(timestamp));
    }

    @Test
    void testWindowDoesNotContainTimestampOutside() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(1000);
        String element = "test";
        long timestamp = 5000;

        // When
        Iterable<WindowAssigner.Window> windows = sessionWindow.assignWindows(element, timestamp);
        TimeWindow timeWindow = (TimeWindow) windows.iterator().next();

        // Then - window should not contain timestamps outside its range
        assertFalse(timeWindow.contains(timestamp + 1000));
        assertFalse(timeWindow.contains(timestamp - 1));
    }

    @Test
    void testShouldMergeWithTouchingWindows() {
        // Given - windows w1 ending exactly when w2 starts
        // Note: Touching windows (end == start) do NOT intersect in TimeWindow
        TimeWindow w1 = new TimeWindow(1000, 2000);
        TimeWindow w2 = new TimeWindow(2000, 3000);

        // When
        boolean shouldMerge = SessionWindow.shouldMerge(w1, w2);

        // Then - touching windows do NOT intersect
        assertFalse(shouldMerge, "Touching windows do NOT intersect");
    }

    @Test
    void testAssignWindowsForDifferentElements() {
        // Given
        SessionWindow<String> sessionWindow = SessionWindow.withGapMillis(1000);

        // When - assign windows for different elements at different times
        Iterable<WindowAssigner.Window> windows1 = sessionWindow.assignWindows("element1", 1000);
        Iterable<WindowAssigner.Window> windows2 = sessionWindow.assignWindows("element2", 5000);

        // Then
        WindowAssigner.Window w1 = windows1.iterator().next();
        WindowAssigner.Window w2 = windows2.iterator().next();
        assertEquals(1000, w1.getStart());
        assertEquals(2000, w1.getEnd());
        assertEquals(5000, w2.getStart());
        assertEquals(6000, w2.getEnd());
    }
}
