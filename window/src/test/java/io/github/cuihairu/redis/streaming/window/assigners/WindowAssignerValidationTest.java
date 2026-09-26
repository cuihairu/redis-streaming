package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.triggers.CountTrigger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the B-11/B-21/B-39 validation and alignment fixes:
 * ZERO/negative window sizes and slide used to be accepted and then surface as
 * ArithmeticException, infinite assignment loops or silently-inverted bounds;
 * pre-epoch timestamps aligned with {@code %} landed in the wrong window.
 */
class WindowAssignerValidationTest {

    @Test
    void tumblingWindowRejectsZeroAndNegativeSize() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> TumblingWindow.ofMillis(0));
        assertTrue(zero.getMessage().contains("positive"));

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> TumblingWindow.ofMillis(-1000));
        assertTrue(negative.getMessage().contains("positive"));
    }

    @Test
    void slidingWindowRejectsZeroAndNegativeSizeOrSlide() {
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.ofMillis(0, 100));
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.ofMillis(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.ofMillis(1000, 0));
        assertThrows(IllegalArgumentException.class, () -> SlidingWindow.ofMillis(1000, -1));
    }

    @Test
    void countTriggerRejectsZeroAndNegativeMaxCount() {
        assertThrows(IllegalArgumentException.class, () -> CountTrigger.of(0));
        assertThrows(IllegalArgumentException.class, () -> CountTrigger.of(-3));
    }

    @Test
    void tumblingWindowAlignsPreEpochTimestampsIntoTheWindowBeforeThem() {
        // B-21: -1 % 1000 == -1 used to shift the start to 0, producing [0,1000) which does
        // not even contain ts=-1. floorMod aligns to [-1000,0).
        TumblingWindow<String> window = TumblingWindow.ofMillis(1000);
        WindowAssigner.Window assigned = window.assignWindows("e", -1).iterator().next();
        assertEquals(-1000L, assigned.getStart());
        assertEquals(0L, assigned.getEnd());
    }

    @Test
    void slidingWindowAssignsPreEpochTimestampsToWindowsThatContainThem() {
        // B-21: with % the lastStart computation drifted for negative timestamps; every
        // generated window must actually contain the element timestamp.
        SlidingWindow<String> window = SlidingWindow.ofMillis(1000, 300);
        List<WindowAssigner.Window> windows = new ArrayList<>();
        window.assignWindows("e", -1).forEach(windows::add);
        assertFalse(windows.isEmpty());
        for (WindowAssigner.Window w : windows) {
            assertTrue(w.getStart() <= -1, w + " must start at or before the element");
            assertTrue(-1 < w.getEnd(), w + " must contain the element timestamp");
        }
    }
}
