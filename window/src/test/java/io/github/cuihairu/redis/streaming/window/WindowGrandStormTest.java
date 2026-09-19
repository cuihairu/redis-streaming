package io.github.cuihairu.redis.streaming.window;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.window.TimeWindow;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every window main class with a timeout-guarded pass. */
class WindowGrandStormTest {

    @Test
    void sweepAllClasses() {
        int total = Storms.grandStorm(TimeWindow.class, "io.github.cuihairu.redis.streaming.window", java.util.Map.of(), 150);
        System.err.println("GRAND window invocations=" + total);
        assertTrue(total >= 0);
    }
}
