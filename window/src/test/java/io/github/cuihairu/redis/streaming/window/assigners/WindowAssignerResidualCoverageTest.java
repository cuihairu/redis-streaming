package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Residual semantics for the assigner contracts the happy-path suites do not pin down:
 * the merging flag (session yes, tumbling/sliding no — the {@link WindowAssigner} default)
 * and the sliding assignment branch that drops the oldest window when it ends exactly at
 * the element's timestamp.
 */
class WindowAssignerResidualCoverageTest {

    @Test
    void onlySessionWindowsSupportMerging() {
        assertTrue(SessionWindow.withGapMillis(5_000).supportsWindowMerging(),
                "session windows must declare merge support (overlaps coalesce into the union)");
        assertFalse(TumblingWindow.ofMillis(5_000).supportsWindowMerging(),
                "tumbling windows never overlap — the interface default (false) must hold");
        assertFalse(SlidingWindow.ofMillis(10_000, 5_000).supportsWindowMerging(),
                "sliding windows intentionally keep shared elements in separate buckets");
    }

    @Test
    void slidingWindowDropsTheWindowEndingExactlyAtTheTimestamp() {
        // ts=15_000 with size=10s slide=5s: candidate starts 15_000, 10_000, 5_000, 0 —
        // the [0, 10_000) window ends exactly at the timestamp, so start+size > timestamp
        // is false there and it must NOT receive the element (half-open [start, end))
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 5_000);

        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", 15_000).forEach(windows::add);

        assertEquals(2, windows.size());
        // windows are produced newest-start first (the loop walks starts backwards)
        assertEquals(new TimeWindow(15_000, 25_000), windows.get(0));
        assertEquals(new TimeWindow(10_000, 20_000), windows.get(1));
        assertFalse(windows.contains(new TimeWindow(0, 10_000)),
                "a window ending exactly at the timestamp must not contain the element");
    }

    @Test
    void slidingGuardRejectsWindowsWhoseEndOverflows() {
        // the `start + size > timestamp` guard also shields against overflow: near
        // Long.MAX_VALUE the wrapped start+size goes negative and such windows must be
        // dropped (empty result, no negative-span window, no exception)
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(2_000, 2);

        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", Long.MAX_VALUE).forEach(windows::add);

        assertTrue(windows.isEmpty(),
                "overflowing window ends must be excluded by the guard");
    }

    @Test
    void slidingWindowAlignsPreEpochTimestampsWithFloorMod() {
        // B-21 in the sliding variant: a pre-epoch timestamp must land in the window
        // BEFORE it (floor alignment), not be pushed forward by a truncated % remainder
        SlidingWindow<String> assigner = SlidingWindow.ofMillis(10_000, 10_000);

        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("e", -1).forEach(windows::add);

        assertEquals(1, windows.size());
        TimeWindow w = (TimeWindow) windows.get(0);
        assertEquals(-10_000, w.getStart(), "-1ms must floor-align into [-10_000, 0)");
        assertEquals(0, w.getEnd());
    }
}
