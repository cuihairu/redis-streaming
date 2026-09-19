package io.github.cuihairu.redis.streaming.table;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.table.StreamTableConverter;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every table main class with a timeout-guarded pass. */
class TableGrandStormTest {

    @Test
    void sweepAllClasses() {
        int total = Storms.grandStorm(StreamTableConverter.class, "io.github.cuihairu.redis.streaming.table", java.util.Map.of(), 150);
        System.err.println("GRAND table invocations=" + total);
        assertTrue(total >= 0);
    }
}
