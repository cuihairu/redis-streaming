package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SessionWindow
 */
class SessionWindowAssignerTest {

    @Test
    void testWithGapDuration() {
        SessionWindow<String> window = SessionWindow.withGap(Duration.ofSeconds(30));

        assertEquals(30000L, window.getSessionGap());
        assertEquals("SessionWindow{gap=30000ms}", window.toString());
    }

    @Test
    void testWithGapMillis() {
        SessionWindow<Integer> window = SessionWindow.withGapMillis(5000L);

        assertEquals(5000L, window.getSessionGap());
    }

    @Test
    void testAssignWindowsCreatesSessionFromTimestamp() {
        SessionWindow<String> window = SessionWindow.withGapMillis(1000L);

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", 5000L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(5000L, w.getStart());
        assertEquals(6000L, w.getEnd());
        assertFalse(windows.hasNext());
    }

    @Test
    void testAssignWindowsWithZeroGap() {
        SessionWindow<String> window = SessionWindow.withGapMillis(0L);

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", 5000L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(5000L, w.getStart());
        assertEquals(5000L, w.getEnd());
    }

    @Test
    void testAssignWindowsWithLargeGap() {
        SessionWindow<String> window = SessionWindow.withGapMillis(3600000L); // 1 hour

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", 5000L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(5000L, w.getStart());
        assertEquals(3605000L, w.getEnd());
    }

    @Test
    void testAssignWindowsNegativeTimestamp() {
        SessionWindow<String> window = SessionWindow.withGapMillis(1000L);

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", -1000L).iterator();

        assertTrue(windows.hasNext());
        TimeWindow w = (TimeWindow) windows.next();
        assertEquals(-1000L, w.getStart());
        assertEquals(0L, w.getEnd());
    }

    @Test
    void testAssignWindowsAlwaysReturnsSingleWindow() {
        SessionWindow<String> window = SessionWindow.withGapMillis(5000L);

        for (long ts = 0L; ts < 10000L; ts += 123L) {
            int count = 0;
            for (Iterator<?> it = window.assignWindows("test", ts).iterator(); it.hasNext(); ) {
                it.next();
                count++;
            }
            assertEquals(1, count);
        }
    }

    @Test
    void testGetDefaultTrigger() {
        SessionWindow<String> window = SessionWindow.withGapMillis(1000L);

        assertNotNull(window.getDefaultTrigger());
        assertTrue(window.getDefaultTrigger() instanceof io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Trigger);
    }

    @Test
    void testToString() {
        SessionWindow<String> window = SessionWindow.withGapMillis(10000L);

        String str = window.toString();
        assertTrue(str.contains("SessionWindow"));
        assertTrue(str.contains("10000"));
    }

    @Test
    void testShouldMergeWithIntersectingWindows() {
        TimeWindow w1 = new TimeWindow(1000L, 3000L);
        TimeWindow w2 = new TimeWindow(2500L, 4500L);

        assertTrue(SessionWindow.shouldMerge(w1, w2));
        assertTrue(SessionWindow.shouldMerge(w2, w1));
    }

    @Test
    void testShouldMergeWithAdjacentWindows() {
        TimeWindow w1 = new TimeWindow(1000L, 3000L);
        TimeWindow w2 = new TimeWindow(3000L, 5000L);

        assertFalse(SessionWindow.shouldMerge(w1, w2));
        assertFalse(SessionWindow.shouldMerge(w2, w1));
    }

    @Test
    void testShouldMergeWithNonOverlappingWindows() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(3000L, 4000L);

        assertFalse(SessionWindow.shouldMerge(w1, w2));
        assertFalse(SessionWindow.shouldMerge(w2, w1));
    }

    @Test
    void testShouldMergeWithSameWindow() {
        TimeWindow w1 = new TimeWindow(1000L, 2000L);
        TimeWindow w2 = new TimeWindow(1000L, 2000L);

        assertTrue(SessionWindow.shouldMerge(w1, w2));
    }

    @Test
    void testShouldMergeWithOneWindowWithinAnother() {
        TimeWindow w1 = new TimeWindow(1000L, 5000L);
        TimeWindow w2 = new TimeWindow(2000L, 3000L);

        assertTrue(SessionWindow.shouldMerge(w1, w2));
        assertTrue(SessionWindow.shouldMerge(w2, w1));
    }

    @Test
    void testSessionMergingScenario() {
        long gap = 5000L;
        SessionWindow<String> window = SessionWindow.withGapMillis(gap);

        // Event at timestamp 1000 creates window [1000, 6000]
        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> w1 =
                window.assignWindows("event1", 1000L).iterator();
        TimeWindow session1 = (TimeWindow) w1.next();

        // Event at timestamp 3000 creates window [3000, 8000]
        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> w2 =
                window.assignWindows("event2", 3000L).iterator();
        TimeWindow session2 = (TimeWindow) w2.next();

        // Windows should intersect (3000 < 6000 and 1000 < 8000)
        assertTrue(TimeWindow.intersects(session1, session2));
        assertTrue(SessionWindow.shouldMerge(session1, session2));
    }

    @Test
    void testSessionNonMergingScenario() {
        long gap = 2000L;
        SessionWindow<String> window = SessionWindow.withGapMillis(gap);

        // Event at timestamp 1000 creates window [1000, 3000]
        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> w1 =
                window.assignWindows("event1", 1000L).iterator();
        TimeWindow session1 = (TimeWindow) w1.next();

        // Event at timestamp 5000 creates window [5000, 7000]
        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> w2 =
                window.assignWindows("event2", 5000L).iterator();
        TimeWindow session2 = (TimeWindow) w2.next();

        // Windows should not intersect (3000 <= 3000 and 5000 >= 5000 - adjacent is not intersecting)
        assertFalse(TimeWindow.intersects(session1, session2));
        assertFalse(SessionWindow.shouldMerge(session1, session2));
    }

    @Test
    void testDifferentGapSizes() {
        assertEquals(1000L, SessionWindow.withGap(Duration.ofMillis(1000)).getSessionGap());
        assertEquals(1000L, SessionWindow.withGap(Duration.ofSeconds(1)).getSessionGap());
        assertEquals(60000L, SessionWindow.withGap(Duration.ofMinutes(1)).getSessionGap());
        assertEquals(3600000L, SessionWindow.withGap(Duration.ofHours(1)).getSessionGap());
    }

    @Test
    void testSessionContainsOriginalTimestamp() {
        SessionWindow<String> window = SessionWindow.withGapMillis(5000L);
        long timestamp = 12345L;

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", timestamp).iterator();
        TimeWindow session = (TimeWindow) windows.next();

        assertTrue(session.contains(timestamp),
                "Session window should contain the timestamp that created it");
    }

    @Test
    void testWindowEndIsTimestampPlusGap() {
        SessionWindow<String> window = SessionWindow.withGapMillis(3000L);
        long timestamp = 5000L;

        Iterator<io.github.cuihairu.redis.streaming.api.stream.WindowAssigner.Window> windows =
                window.assignWindows("test", timestamp).iterator();
        TimeWindow session = (TimeWindow) windows.next();

        assertEquals(timestamp + 3000L, session.getEnd());
        assertEquals(3000L, session.getSize());
    }
}
