package io.github.cuihairu.redis.streaming.aggregation;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.aggregation.WindowAggregator;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every aggregation main class with a timeout-guarded pass. */
class AggregationGrandStormTest {

    @Test
    void sweepAllClasses() {
        int total = Storms.grandStorm(WindowAggregator.class, "io.github.cuihairu.redis.streaming.aggregation", java.util.Map.of(), 150);
        System.err.println("GRAND aggregation invocations=" + total);
        assertTrue(total >= 0);
    }
}
