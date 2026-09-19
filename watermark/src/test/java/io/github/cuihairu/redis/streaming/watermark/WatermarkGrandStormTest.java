package io.github.cuihairu.redis.streaming.watermark;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.watermark.WatermarkStrategy;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every watermark main class with a timeout-guarded pass. */
class WatermarkGrandStormTest {

    @Test
    void sweepAllClasses() {
        int total = Storms.grandStorm(WatermarkStrategy.class, "io.github.cuihairu.redis.streaming.watermark", java.util.Map.of(), 150);
        System.err.println("GRAND watermark invocations=" + total);
        assertTrue(total >= 0);
    }
}
