package io.github.cuihairu.redis.streaming.examples.window;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke + semantics test for {@link WindowExample}. The example's demo methods were made
 * package-visible so they can be driven from tests directly (example-side change only);
 * this covers the whole example flow without needing a console run.
 */
class WindowExampleTest {

    @Test
    void mainAndEveryDemoSectionRunWithoutThrowing() {
        assertDoesNotThrow(() -> WindowExample.demonstrateTumblingWindow());
        assertDoesNotThrow(() -> WindowExample.demonstrateSlidingWindow());
        assertDoesNotThrow(() -> WindowExample.demonstrateSessionWindow());
        assertDoesNotThrow(() -> WindowExample.demonstrateWindowOperations());
        assertDoesNotThrow(() -> WindowExample.main(new String[0]));
    }

    @Test
    void tumblingSectionSplitsEventsIntoTwoDistinctWindows() {
        // mirrors demonstrateTumblingWindow's fixture: base..base+3s in one 5s window,
        // base+6s..base+7s in the next
        WindowAssigner<String> assigner = io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow
                .of(Duration.ofSeconds(5));
        long base = 1_000_000L;

        List<WindowAssigner.Window> first = new ArrayList<>();
        assigner.assignWindows("event2", base + 1_000).forEach(first::add);
        List<WindowAssigner.Window> second = new ArrayList<>();
        assigner.assignWindows("event4", base + 6_000).forEach(second::add);

        assertEquals(new TimeWindow(1_000_000, 1_005_000), first.get(0));
        assertEquals(new TimeWindow(1_005_000, 1_010_000), second.get(0));
    }

    @Test
    void slidingSectionPutsTheSampleEventInTwoOverlappingWindows() {
        // mirrors demonstrateSlidingWindow: size 10s slide 5s, event at +12s
        io.github.cuihairu.redis.streaming.window.assigners.SlidingWindow<String> assigner =
                io.github.cuihairu.redis.streaming.window.assigners.SlidingWindow.of(
                        Duration.ofSeconds(10), Duration.ofSeconds(5));
        long base = 1_000_000L;

        List<WindowAssigner.Window> windows = new ArrayList<>();
        assigner.assignWindows("event", base + 12_000).forEach(windows::add);

        assertEquals(2, windows.size());
        assertTrue(windows.stream().allMatch(w -> base + 12_000 >= w.getStart()
                && base + 12_000 < w.getEnd()), "every assigned window must contain the event");
    }

    @Test
    void sessionSectionClicksOneAndTwoShareAGapBelowTheSessionTimeout() {
        io.github.cuihairu.redis.streaming.window.assigners.SessionWindow<String> assigner =
                io.github.cuihairu.redis.streaming.window.assigners.SessionWindow.withGap(Duration.ofSeconds(30));
        long base = 1_000_000L;

        TimeWindow click1 = (TimeWindow) assigner.assignWindows("click1", base).iterator().next();
        TimeWindow click2 = (TimeWindow) assigner.assignWindows("click2", base + 10_000).iterator().next();
        TimeWindow click3 = (TimeWindow) assigner.assignWindows("click3", base + 50_000).iterator().next();

        assertTrue(io.github.cuihairu.redis.streaming.window.assigners.SessionWindow.shouldMerge(click1, click2),
                "10s apart with a 30s gap — same session");
        assertFalse(io.github.cuihairu.redis.streaming.window.assigners.SessionWindow.shouldMerge(click2, click3),
                "40s after click2's seed — a new session");
    }
}
