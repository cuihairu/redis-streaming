package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}

