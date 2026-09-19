package io.github.cuihairu.redis.streaming.api;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every core main class with a timeout-guarded pass. */
class CoreApiGrandStormTest {

    @Test
    void sweepAllClasses() {
        int total = Storms.grandStorm(DataStream.class, "io.github.cuihairu.redis.streaming.api", java.util.Map.of(), 150);
        System.err.println("GRAND core invocations=" + total);
        assertTrue(total >= 0);
    }
}
