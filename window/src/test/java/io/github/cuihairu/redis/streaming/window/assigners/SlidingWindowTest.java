package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SlidingWindow
 */
class SlidingWindowTest {

    @Test
    void testOfWithDuration() {
        // Given
        Duration size = Duration.ofSeconds(10);
        Duration slide = Duration.ofSeconds(5);

        // When
        SlidingWindow<String> window = SlidingWindow.of(size, slide);

        // Then
        assertEquals(10000, window.getSize());
        assertEquals(5000, window.getSlide());
    }

    @Test
    void testOfWithMillis() {
        // Given
        long size = 10000;
        long slide = 5000;

        // When
        SlidingWindow<String> window = SlidingWindow.ofMillis(size, slide);

        // Then
        assertEquals(size, window.getSize());
        assertEquals(slide, window.getSlide());
    }

    @Test
    void testAssignWindowsSingleWindow() {
        // Given - 10 second window with 5 second slide
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));
        String element = "test";
        long timestamp = 10000; // 10 seconds

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - should assign to 2 windows: [0, 10000) and [5000, 15000)
        assertEquals(2, windowList.size());
        assertTrue(windowList.get(0) instanceof TimeWindow);
        assertTrue(windowList.get(1) instanceof TimeWindow);
    }

    @Test
    void testAssignWindowsAtBoundary() {
        // Given
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));
        String element = "test";
        long timestamp = 5000; // exactly at slide boundary

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then
        assertEquals(2, windowList.size());
    }

    @Test
    void testAssignWindowsWithZeroTimestamp() {
        // Given
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));
        String element = "test";
        long timestamp = 0;

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - lastStart=0, start=0: 0 > -10000 and 0+10000>0 -> add [0,10000)
        //       start=-5000: -5000 > -10000 and -5000+10000>0 -> add [-5000,5000)
        //       start=-10000: -10000 > -10000 is false -> stop
        assertEquals(2, windowList.size());
        TimeWindow tw0 = (TimeWindow) windowList.get(0);
        assertEquals(0, tw0.getStart());
        assertEquals(10000, tw0.getEnd());
        TimeWindow tw1 = (TimeWindow) windowList.get(1);
        assertEquals(-5000, tw1.getStart());
        assertEquals(5000, tw1.getEnd());
    }

    @Test
    void testGetSize() {
        // Given
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofMinutes(5), Duration.ofMinutes(1));

        // When
        long size = window.getSize();

        // Then
        assertEquals(300000, size); // 5 minutes in millis
    }

    @Test
    void testGetSlide() {
        // Given
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofMinutes(5), Duration.ofMinutes(1));

        // When
        long slide = window.getSlide();

        // Then
        assertEquals(60000, slide); // 1 minute in millis
    }

    @Test
    void testToString() {
        // Given
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));

        // When
        String str = window.toString();

        // Then
        assertTrue(str.contains("SlidingWindow"));
        assertTrue(str.contains("size=10000"));
        assertTrue(str.contains("slide=5000"));
    }

    @Test
    void testGetDefaultTrigger() {
        // Given
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));

        // When
        var trigger = window.getDefaultTrigger();

        // Then
        assertNotNull(trigger);
        // Default trigger should be EventTimeTrigger
        assertTrue(trigger.getClass().getSimpleName().contains("EventTimeTrigger"));
    }

    @Test
    void testAssignWindowsOverlapping() {
        // Given - 15 second window, 10 second slide
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(15), Duration.ofSeconds(10));
        String element = "test";
        long timestamp = 12000;

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - should assign to 2 windows with overlap
        assertEquals(2, windowList.size());
    }

    @Test
    void testAssignWindowsLargeSlide() {
        // Given - 5 second window, 10 second slide (slide > size)
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(5), Duration.ofSeconds(10));
        String element = "test";
        long timestamp = 12000;

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - should assign to only 1 window
        assertEquals(1, windowList.size());
    }

    @Test
    void testAssignWindowsEqualSizeAndSlide() {
        // Given - size equals slide (tumbling window behavior)
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(10));
        String element = "test";
        long timestamp = 15000;

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - should assign to only 1 window
        assertEquals(1, windowList.size());
        TimeWindow tw = (TimeWindow) windowList.get(0);
        assertEquals(10000, tw.getStart());
        assertEquals(20000, tw.getEnd());
    }

    @Test
    void testAssignWindowsMultipleOverlaps() {
        // Given - 30 second window, 5 second slide
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(30), Duration.ofSeconds(5));
        String element = "test";
        long timestamp = 25000;

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - should assign to multiple windows (6 windows)
        assertTrue(windowList.size() > 1);
    }

    @Test
    void testAssignWindowsVerySmallSlide() {
        // Given - 10 second window, 1 second slide
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(1));
        String element = "test";
        long timestamp = 5500;

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - should assign to 10 windows
        assertEquals(10, windowList.size());
    }

    @Test
    void testWindowSizeIsMultipleOfSlide() {
        // Given - size is exactly multiple of slide
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(2));
        String element = "test";
        long timestamp = 9000;

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - should assign to 5 windows (10/2 = 5)
        assertEquals(5, windowList.size());
    }

    @Test
    void testWindowNotIncludingTimestamp() {
        // Given
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));
        String element = "test";
        long timestamp = 100;

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - timestamp 100 should be in window [0, 10000)
        TimeWindow tw = (TimeWindow) windowList.get(0);
        assertTrue(timestamp >= tw.getStart());
        assertTrue(timestamp < tw.getEnd());
    }

    @Test
    void testAssignWindowsWithLargeTimestamp() {
        // Given - large timestamp value
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofHours(1), Duration.ofMinutes(15));
        String element = "test";
        long timestamp = 3_600_000 * 24; // 24 hours in millis

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - should assign to 4 windows (1 hour / 15 min = 4)
        assertEquals(4, windowList.size());
    }

    @Test
    void testWindowEndIsExclusive() {
        // Given
        SlidingWindow<String> window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));
        String element = "test";
        long timestamp = 9999; // just before window boundary

        // When
        Iterable<WindowAssigner.Window> windows = window.assignWindows(element, timestamp);
        List<WindowAssigner.Window> windowList = new ArrayList<>();
        windows.forEach(windowList::add);

        // Then - timestamp 9999 should be in windows [5000,15000) and [0,10000)
        assertEquals(2, windowList.size());
        TimeWindow tw0 = (TimeWindow) windowList.get(0);
        assertEquals(5000, tw0.getStart());
        assertEquals(15000, tw0.getEnd());
        TimeWindow tw1 = (TimeWindow) windowList.get(1);
        assertEquals(0, tw1.getStart());
        assertEquals(10000, tw1.getEnd());
        assertTrue(timestamp >= tw1.getStart());
        assertTrue(timestamp < tw1.getEnd());
    }
}
