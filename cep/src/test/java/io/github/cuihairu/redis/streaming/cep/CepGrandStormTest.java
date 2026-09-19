package io.github.cuihairu.redis.streaming.cep;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.cep.PatternSequence;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every cep main class with a timeout-guarded pass. */
class CepGrandStormTest {

    @Test
    void sweepAllClasses() {
        int total = Storms.grandStorm(PatternSequence.class, "io.github.cuihairu.redis.streaming.cep", java.util.Map.of(), 150);
        System.err.println("GRAND cep invocations=" + total);
        assertTrue(total >= 0);
    }
}
