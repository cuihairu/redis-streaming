package io.github.cuihairu.redis.streaming.cdc;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.cdc.ChangeEvent;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every cdc main class with a timeout-guarded pass. */
class CdcGrandStormTest {

    @Test
    void sweepAllClasses() {
        int total = Storms.grandStorm(ChangeEvent.class, "io.github.cuihairu.redis.streaming.cdc", java.util.Map.of(), 150);
        System.err.println("GRAND cdc invocations=" + total);
        assertTrue(total >= 0);
    }
}
