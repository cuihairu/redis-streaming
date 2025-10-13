package io.github.cuihairu.redis.streaming.window;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.assigners.SlidingWindow;
import io.github.cuihairu.redis.streaming.window.assigners.SessionWindow;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Window module
 */
@Slf4j
public class WindowTest {

    @Test
    public void testTumblingWindow() {
        log.info("Testing TumblingWindow");

        TumblingWindow<String> assigner = TumblingWindow.of(Duration.ofMinutes(1));

        // Test window assignment
        long timestamp1 = 60000; // 1 minute
        long timestamp2 = 65000; // 1 minute 5 seconds
        long timestamp3 = 120000; // 2 minutes

        List<WindowAssigner.Window> windows1 = new ArrayList<>();
        assigner.assignWindows("event1", timestamp1).forEach(windows1::add);
        assertEquals(1, windows1.size());
        assertEquals(60000, windows1.get(0).getStart());
        assertEquals(120000, windows1.get(0).getEnd());

        List<WindowAssigner.Window> windows2 = new ArrayList<>();
        assigner.assignWindows("event2", timestamp2).forEach(windows2::add);
        assertEquals(1, windows2.size());
        assertEquals(windows1.get(0), windows2.get(0)); // Same window

        List<WindowAssigner.Window> windows3 = new ArrayList<>();
        assigner.assignWindows("event3", timestamp3).forEach(windows3::add);
        assertEquals(1, windows3.size());
        assertEquals(120000, windows3.get(0).getStart());
        assertEquals(180000, windows3.get(0).getEnd());

        log.info("TumblingWindow test passed");
    }

    @Test
    public void testSlidingWindow() {
        log.info("Testing SlidingWindow");

        SlidingWindow<String> assigner = SlidingWindow.of(
                Duration.ofMinutes(2),  // window size
                Duration.ofMinutes(1)   // slide
        );

        long timestamp = 125000; // 2 minutes 5 seconds

        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("event", timestamp).forEach(windows::add);

        // Should be assigned to 2 overlapping windows
        assertTrue(windows.size() >= 1);

        log.info("Assigned to {} windows", windows.size());
        for (WindowAssigner.Window window : windows) {
            log.info("  Window: {} - {}", window.getStart(), window.getEnd());
            assertTrue(timestamp >= window.getStart());
            assertTrue(timestamp < window.getEnd());
        }

        log.info("SlidingWindow test passed");
    }

    @Test
    public void testSessionWindow() {
        log.info("Testing SessionWindow");

        SessionWindow<String> assigner = SessionWindow.withGap(Duration.ofSeconds(30));

        long timestamp = 60000;

        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("event", timestamp).forEach(windows::add);

        assertEquals(1, windows.size());
        assertEquals(60000, windows.get(0).getStart());
        assertEquals(90000, windows.get(0).getEnd()); // timestamp + 30s gap

        log.info("SessionWindow test passed");
    }

    @Test
    public void testTimeWindow() {
        log.info("Testing TimeWindow");

        TimeWindow window = new TimeWindow(0, 60000);

        assertEquals(0, window.getStart());
        assertEquals(60000, window.getEnd());
        assertEquals(59999, window.maxTimestamp());
        assertEquals(60000, window.getSize());

        assertTrue(window.contains(0));
        assertTrue(window.contains(30000));
        assertFalse(window.contains(60000));
        assertFalse(window.contains(-1));

        log.info("TimeWindow test passed");
    }

    @Test
    public void testTimeWindowMerge() {
        log.info("Testing TimeWindow merge");

        TimeWindow w1 = new TimeWindow(0, 60000);
        TimeWindow w2 = new TimeWindow(30000, 90000);

        assertTrue(TimeWindow.intersects(w1, w2));

        TimeWindow merged = TimeWindow.merge(w1, w2);
        assertEquals(0, merged.getStart());
        assertEquals(90000, merged.getEnd());

        log.info("TimeWindow merge test passed");
    }

    @Test
    public void testWindowEquality() {
        TimeWindow w1 = new TimeWindow(0, 60000);
        TimeWindow w2 = new TimeWindow(0, 60000);
        TimeWindow w3 = new TimeWindow(0, 120000);

        assertEquals(w1, w2);
        assertNotEquals(w1, w3);
        assertEquals(w1.hashCode(), w2.hashCode());
    }
}
