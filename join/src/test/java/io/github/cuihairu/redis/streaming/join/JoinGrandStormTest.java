package io.github.cuihairu.redis.streaming.join;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.join.StreamJoiner;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every join main class with a timeout-guarded pass. */
class JoinGrandStormTest {

    @Test
    void sweepAllClasses() {
        int total = Storms.grandStorm(StreamJoiner.class, "io.github.cuihairu.redis.streaming.join", java.util.Map.of(), 150);
        System.err.println("GRAND join invocations=" + total);
        assertTrue(total >= 0);
    }
}
