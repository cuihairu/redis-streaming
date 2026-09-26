package io.github.cuihairu.redis.streaming.aggregation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the B-11/B-21 fixes in the aggregation-module windows:
 * ZERO/negative (or null) durations used to be accepted and later surfaced as
 * ArithmeticException, infinite overlap loops or silently-inverted bounds, and
 * pre-epoch timestamps aligned via truncating division landed in the wrong window.
 */
class WindowValidationTest {

    @Test
    void tumblingWindowRejectsZeroNegativeAndNullSize() {
        assertThrows(IllegalArgumentException.class, () -> TumblingWindow.of(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> TumblingWindow.of(Duration.ofMillis(-5)));
        assertThrows(IllegalArgumentException.class, () -> TumblingWindow.of(null));
        // Direct construction must be guarded the same way as the factories
        assertThrows(IllegalArgumentException.class, () -> new TumblingWindow(Duration.ZERO));
    }

    @Test
    void slidingWindowRejectsZeroNegativeAndNullDurations() {
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.of(Duration.ofSeconds(10), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.of(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.of(Duration.ofSeconds(10), Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.of(null, Duration.ofSeconds(1)));
        // The pre-existing slide > size guard stays intact
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.of(Duration.ofSeconds(1), Duration.ofSeconds(10)));
        // Direct construction must be guarded the same way as the factories
        assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindow(Duration.ofSeconds(10), Duration.ZERO));
    }

    @Test
    void tumblingWindowAlignsPreEpochTimestampsIntoTheWindowBeforeThem() {
        // B-21: -1 / 1000 truncated to 0 used to yield window start 0 (i.e. the window AFTER
        // the timestamp). floorDiv aligns ts=-1ms into [-1s, 0).
        TumblingWindow window = TumblingWindow.ofSeconds(1);
        assertEquals(Instant.ofEpochMilli(-1000), window.getWindowStart(Instant.ofEpochMilli(-1)));
        assertEquals(Instant.ofEpochMilli(0), window.getWindowEnd(Instant.ofEpochMilli(-1)));
    }

    @Test
    void slidingWindowAlignsPreEpochTimestampsAndOverlapsContainTheElement() {
        // B-21: getWindowStart/getOverlappingWindows used truncating division, misplacing
        // pre-epoch timestamps; every overlapping window must contain the element.
        SlidingWindow window = SlidingWindow.of(Duration.ofSeconds(1), Duration.ofMillis(300));
        Instant ts = Instant.ofEpochMilli(-1);
        assertEquals(Instant.ofEpochMilli(-300), window.getWindowStart(ts));

        assertFalse(window.getOverlappingWindows(ts).isEmpty());
        for (Instant start : window.getOverlappingWindows(ts)) {
            Instant end = start.plus(Duration.ofSeconds(1));
            assertTrue(!start.isAfter(ts) && ts.isBefore(end),
                    "window [" + start + "," + end + ") must contain " + ts);
        }
    }
}
