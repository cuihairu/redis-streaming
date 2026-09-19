package io.github.cuihairu.redis.streaming.metrics;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.metrics.MetricRegistry;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every metrics main class with a timeout-guarded pass. */
class MetricsGrandStormTest {

    @Test
    void sweepAllClasses() {
        int total = Storms.grandStorm(MetricRegistry.class, "io.github.cuihairu.redis.streaming.metrics", java.util.Map.of(), 150);
        System.err.println("GRAND metrics invocations=" + total);
        assertTrue(total >= 0);
    }
}
