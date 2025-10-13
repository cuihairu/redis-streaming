package io.github.cuihairu.redis.streaming.aggregation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowTest {

    @Test
    void testSlidingWindowCreation() {
        Duration size = Duration.ofMinutes(10);
        Duration slide = Duration.ofMinutes(2);
        SlidingWindow window = SlidingWindow.of(size, slide);

        assertEquals(size, window.getSize());
        assertEquals(slide, window.getSlide());
        assertTrue(window.isSliding());
    }

    @Test
    void testInvalidSlidingWindow() {
        Duration size = Duration.ofMinutes(5);
        Duration slide = Duration.ofMinutes(10); // Slide larger than size

        assertThrows(IllegalArgumentException.class, () -> {
            SlidingWindow.of(size, slide);
        });
    }

    @Test
    void testSlidingWindowFactoryMethods() {
        SlidingWindow secondsWindow = SlidingWindow.ofSeconds(60, 10);
        assertEquals(Duration.ofSeconds(60), secondsWindow.getSize());
        assertEquals(Duration.ofSeconds(10), secondsWindow.getSlide());

        SlidingWindow minutesWindow = SlidingWindow.ofMinutes(10, 2);
        assertEquals(Duration.ofMinutes(10), minutesWindow.getSize());
        assertEquals(Duration.ofMinutes(2), minutesWindow.getSlide());
    }

    @Test
    void testOverlappingWindows() {
        // 10-minute window sliding every 2 minutes
        SlidingWindow window = SlidingWindow.ofMinutes(10, 2);

        Instant timestamp = Instant.parse("2023-01-01T10:07:00Z");
        List<Instant> overlappingWindows = window.getOverlappingWindows(timestamp);

        // The timestamp should be in multiple overlapping windows
        assertFalse(overlappingWindows.isEmpty());
        assertTrue(overlappingWindows.size() > 1);

        // Verify each window contains the timestamp
        for (Instant windowStart : overlappingWindows) {
            assertTrue(window.contains(timestamp, windowStart));
        }
    }

    @Test
    void testWindowBounds() {
        SlidingWindow window = SlidingWindow.ofMinutes(10, 5);

        Instant timestamp = Instant.parse("2023-01-01T10:07:00Z");
        Instant windowStart = window.getWindowStart(timestamp);
        Instant windowEnd = window.getWindowEnd(timestamp);

        // Window should start at a 5-minute boundary and be 10 minutes long
        assertEquals(Duration.ofMinutes(10), Duration.between(windowStart, windowEnd));
    }

    @Test
    void testContains() {
        SlidingWindow window = SlidingWindow.ofMinutes(10, 5);
        Instant windowStart = Instant.parse("2023-01-01T10:00:00Z");

        // Test timestamps within the 10-minute window
        assertTrue(window.contains(Instant.parse("2023-01-01T10:00:00Z"), windowStart));
        assertTrue(window.contains(Instant.parse("2023-01-01T10:05:00Z"), windowStart));
        assertTrue(window.contains(Instant.parse("2023-01-01T10:09:59Z"), windowStart));

        // Test timestamps outside the window
        assertFalse(window.contains(Instant.parse("2023-01-01T09:59:59Z"), windowStart));
        assertFalse(window.contains(Instant.parse("2023-01-01T10:10:00Z"), windowStart));
    }

    @Test
    void testNonOverlappingCase() {
        // When slide equals size, it behaves like a tumbling window
        SlidingWindow window = SlidingWindow.ofMinutes(5, 5);
        assertFalse(window.isSliding()); // Should return false since slide == size

        Instant timestamp = Instant.parse("2023-01-01T10:07:00Z");
        List<Instant> overlappingWindows = window.getOverlappingWindows(timestamp);

        // Should only be in one window
        assertEquals(1, overlappingWindows.size());
    }
}