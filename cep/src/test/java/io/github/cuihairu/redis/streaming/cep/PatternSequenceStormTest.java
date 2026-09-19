package io.github.cuihairu.redis.streaming.cep;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;


import static org.junit.jupiter.api.Assertions.*;

/** Fluent construction, validation and error paths of {@link PatternSequence}. */
class PatternSequenceStormTest {

    private static final Pattern<Integer> POSITIVE = x -> x != null && x > 0;
    private static final Pattern<Integer> EVEN = x -> x != null && x % 2 == 0;

    @Test
    void fullFluentChainBuilds() {
        PatternSequence<Integer> seq = PatternSequence.<Integer>begin("a", POSITIVE)
                .next("b", EVEN)
                .where("c", POSITIVE)
                .oneOrMore()
                .followedBy("d", POSITIVE)
                .followedByAny("e", EVEN)
                .zeroOrMore()
                .optional()
                .times(PatternQuantifier.exactly(2));
        assertNotNull(seq);
        assertTrue(seq.size() >= 1);
        assertNotNull(seq.toString());
        seq.validate();
    }

    @Test
    void firstPatternGuardsThrow() {
        assertThrows(IllegalStateException.class, () -> PatternSequence.<Integer>begin().next("x", POSITIVE));
        assertThrows(IllegalStateException.class, () -> PatternSequence.<Integer>begin().followedBy("x", POSITIVE));
        assertThrows(IllegalStateException.class, () -> PatternSequence.<Integer>begin().followedByAny("x", POSITIVE));
        // where() is the additive primitive; begin(name, pattern) builds on it without null guards
        assertEquals(1, PatternSequence.<Integer>begin().where("x", POSITIVE).size());
    }

    @Test
    void quantifierApiAndStorm() {
        PatternSequence<Integer> seq = PatternSequence.<Integer>begin("a", POSITIVE)
                .next("b", EVEN)
                .times(3)
                .times(2, 5)
                .within(java.time.Duration.ofSeconds(10))
                .oneOrMore();
        Map<Class<?>, Object> hints = new HashMap<>();
        hints.put(Pattern.class, POSITIVE);
        hints.put(PatternQuantifier.class, PatternQuantifier.oneOrMore());
        Storms.storm(seq, hints);

        assertNotNull(PatternQuantifier.atLeast(2));
        assertNotNull(PatternQuantifier.zeroOrMore());
        assertNotNull(PatternQuantifier.optional());
        assertEquals(1, PatternQuantifier.exactly(1).getMinOccurrences());
        assertEquals(2, PatternQuantifier.times(1, 2).getMaxOccurrences());
        assertNotNull(PatternQuantifier.exactly(1).getType());
        assertEquals(2, PatternQuantifier.times(1, 2).getMaxOccurrences());
        
        assertNotNull(PatternQuantifier.oneOrMore().toString());
    }
}
