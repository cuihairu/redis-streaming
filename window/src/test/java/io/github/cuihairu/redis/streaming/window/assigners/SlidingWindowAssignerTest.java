package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowAssignerTest {

    @Test
    void assignsOverlappingWindowsDeterministically() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);

        long timestamp = 12_345;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(2, windows.size());
        assertEquals(new TimeWindow(10_000, 20_000), windows.get(0));
        assertEquals(new TimeWindow(5_000, 15_000), windows.get(1));
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }

    @Test
    void assignWindowsWithEqualSlideAndSize() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 10_000);

        long timestamp = 15_000;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(1, windows.size());
        assertEquals(new TimeWindow(10_000, 20_000), windows.get(0));
        assertTrue(timestamp >= windows.get(0).getStart() && timestamp < windows.get(0).getEnd());
    }

    @Test
    void assignWindowsWithTimestampAtBoundary() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);

        long timestamp = 10_000; // Exactly at slide boundary
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(2, windows.size());
        assertEquals(new TimeWindow(10_000, 20_000), windows.get(0));
        assertEquals(new TimeWindow(5_000, 15_000), windows.get(1));
    }

    @Test
    void assignWindowsWithZeroTimestamp() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);

        long timestamp = 0;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(2, windows.size());
        assertEquals(new TimeWindow(0, 10_000), windows.get(0));
        assertEquals(new TimeWindow(-5_000, 5_000), windows.get(1));
    }

    @Test
    void assignWindowsWithLargeSlide() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 15_000);

        long timestamp = 20_000;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(1, windows.size());
        assertEquals(new TimeWindow(15_000, 25_000), windows.get(0));
    }

    @Test
    void assignWindowsWithManyOverlaps() {
        // size=100, slide=10 => up to 10 overlapping windows
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(100, 10);

        long timestamp = 95;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(10, windows.size());
        // All windows should contain the timestamp
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }

    @Test
    void assignWindowsWithDuration() {
        SlidingWindow<String> assigner = SlidingWindow.of(Duration.ofMinutes(5), Duration.ofMinutes(1));

        long timestamp = 180_000; // 3 minutes
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(5, windows.size());
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }

    @Test
    void getSizeReturnsWindowSize() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);
        assertEquals(10_000, assigner.getSize());
    }

    @Test
    void getSlideReturnsWindowSlide() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);
        assertEquals(5_000, assigner.getSlide());
    }

    @Test
    void toStringReturnsCorrectFormat() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);
        String str = assigner.toString();
        assertTrue(str.contains("SlidingWindow"));
        assertTrue(str.contains("size=10000"));
        assertTrue(str.contains("slide=5000"));
    }

    @Test
    void getDefaultTriggerReturnsEventTimeTrigger() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);
        assertNotNull(assigner.getDefaultTrigger());
        assertTrue(assigner.getDefaultTrigger() instanceof io.github.cuihairu.redis.streaming.window.triggers.EventTimeTrigger);
    }

    @Test
    void assignWindowsWithVerySmallSlide() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(1000, 100);

        long timestamp = 550;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(10, windows.size());
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }

    @Test
    void assignWindowsWithNegativeStartBoundary() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(100, 50);

        long timestamp = 25;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(2, windows.size());
        // Windows should start at 0 and -50, both containing timestamp 25
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }

    @Test
    void assignWindowsWithLargeTimestamp() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(60000, 30000); // 1 min size, 30 sec slide

        // Use a large but safe timestamp value (year 2270)
        long timestamp = 9999999999000L;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(2, windows.size());
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }

    @Test
    void assignWindowsOrdering() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);

        long timestamp = 12_345;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        // Windows should be returned in order
        for (int i = 0; i < windows.size() - 1; i++) {
            assertTrue(windows.get(i).getStart() > windows.get(i + 1).getStart(),
                    "Windows should be in descending order of start time");
        }
    }

    @Test
    void assignWindowsWithSingleMillisecondSlide() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(100, 1);

        long timestamp = 50;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(100, windows.size());
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }

    @Test
    void assignWindowsWithSizeOneGreaterThanSlide() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(11, 10);

        long timestamp = 10;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(2, windows.size());
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }

    @Test
    void assignWindowsConsistency() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);

        long timestamp = 12_345;

        // Multiple calls should return consistent results
        List<WindowAssigner.Window> windows1 = new ArrayList<>();
        List<WindowAssigner.Window> windows2 = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows1::add);
        assigner.assignWindows("e", timestamp).forEach(windows2::add);

        assertEquals(windows1.size(), windows2.size());
        for (int i = 0; i < windows1.size(); i++) {
            assertEquals(windows1.get(i), windows2.get(i));
        }
    }

    @Test
    void assignWindowsEdgeCaseAtWindowEnd() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);

        // Timestamp exactly at window end (excluded)
        long timestamp = 20_000;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        // Should assign to new windows, not the one ending at 20_000
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }

    @Test
    void assignWindowsWithVeryLargeSizeToSlideRatio() {
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(1000, 1);

        long timestamp = 500;
        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", timestamp).forEach(windows::add);

        assertEquals(1000, windows.size());
        assertTrue(windows.stream().allMatch(w -> timestamp >= w.getStart() && timestamp < w.getEnd()));
    }
}

