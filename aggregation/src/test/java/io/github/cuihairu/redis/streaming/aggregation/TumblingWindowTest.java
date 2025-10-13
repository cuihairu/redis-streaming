package io.github.cuihairu.redis.streaming.aggregation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TumblingWindowTest {

    @Test
    void testTumblingWindowCreation() {
        Duration size = Duration.ofMinutes(5);
        TumblingWindow window = TumblingWindow.of(size);

        assertEquals(size, window.getSize());
        assertEquals(size, window.getSlide());
        assertFalse(window.isSliding());
    }

    @Test
    void testTumblingWindowFactoryMethods() {
        TumblingWindow secondsWindow = TumblingWindow.ofSeconds(30);
        assertEquals(Duration.ofSeconds(30), secondsWindow.getSize());

        TumblingWindow minutesWindow = TumblingWindow.ofMinutes(5);
        assertEquals(Duration.ofMinutes(5), minutesWindow.getSize());

        TumblingWindow hoursWindow = TumblingWindow.ofHours(1);
        assertEquals(Duration.ofHours(1), hoursWindow.getSize());
    }

    @Test
    void testWindowBounds() {
        TumblingWindow window = TumblingWindow.ofMinutes(5);

        // Test with a specific timestamp: 2023-01-01T10:07:30Z
        Instant timestamp = Instant.parse("2023-01-01T10:07:30Z");

        Instant windowStart = window.getWindowStart(timestamp);
        Instant windowEnd = window.getWindowEnd(timestamp);

        // Should start at 10:05:00 and end at 10:10:00
        assertEquals(Instant.parse("2023-01-01T10:05:00Z"), windowStart);
        assertEquals(Instant.parse("2023-01-01T10:10:00Z"), windowEnd);
    }

    @Test
    void testContains() {
        TumblingWindow window = TumblingWindow.ofMinutes(5);
        Instant windowStart = Instant.parse("2023-01-01T10:05:00Z");

        // Test timestamps within the window
        assertTrue(window.contains(Instant.parse("2023-01-01T10:05:00Z"), windowStart));
        assertTrue(window.contains(Instant.parse("2023-01-01T10:07:30Z"), windowStart));
        assertTrue(window.contains(Instant.parse("2023-01-01T10:09:59Z"), windowStart));

        // Test timestamps outside the window
        assertFalse(window.contains(Instant.parse("2023-01-01T10:04:59Z"), windowStart));
        assertFalse(window.contains(Instant.parse("2023-01-01T10:10:00Z"), windowStart));
    }

    @Test
    void testWindowAlignment() {
        TumblingWindow window = TumblingWindow.ofMinutes(10);

        // Different timestamps should align to the same window
        Instant ts1 = Instant.parse("2023-01-01T10:03:00Z");
        Instant ts2 = Instant.parse("2023-01-01T10:07:00Z");

        assertEquals(window.getWindowStart(ts1), window.getWindowStart(ts2));
        assertEquals(Instant.parse("2023-01-01T10:00:00Z"), window.getWindowStart(ts1));
    }
}