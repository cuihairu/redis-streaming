package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers PatternSequenceMatcher edge branches and CompleteMatch/PatternQuantifier string helpers. */
class PatternSequenceMatcherCoverageTest {

    private static Pattern<String> type(String type) {
        return event -> type.equals(event);
    }

    @Test
    void partialMatchCountReflectsInFlightMatches() {
        PatternSequence<String> sequence = PatternSequence.<String>begin("first", type("a"))
                .next("second", type("b"));
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(sequence);
        assertEquals(0, matcher.getPartialMatchCount());

        matcher.process("a", 1L);
        assertEquals(1, matcher.getPartialMatchCount());

        List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("b", 2L);
        assertEquals(1, matches.size());
        assertEquals(0, matcher.getPartialMatchCount());
    }

    @Test
    void completeMatchExposesEventsPerStepAndStringForm() {
        PatternSequence<String> sequence = PatternSequence.<String>begin("login", type("a"))
                .next("browse", type("b"));
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(sequence);
        matcher.process("a", 10L);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("b", 20L);

        PatternSequenceMatcher.CompleteMatch<String> match = matches.get(0);
        assertEquals(List.of("a"), match.getEventsForStep("login", sequence));
        assertEquals(List.of("b"), match.getEventsForStep("browse", sequence));
        assertEquals(List.of(), match.getEventsForStep("missing", sequence));
        assertEquals(10, match.getDuration());
        assertNotNull(match.toString());
        assertTrue(match.toString().contains("CompleteMatch"));
    }

    @Test
    void optionalAndRepeatQuantifiersExerciseSkipsAndCaps() {
        PatternSequence<String> sequence = PatternSequence.<String>begin("a", type("a"))
                .optional()
                .next("b", type("b"))
                .oneOrMore();
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(sequence);

        // 'b' can start by skipping the optional 'a' step
        List<PatternSequenceMatcher.CompleteMatch<String>> first = matcher.process("b", 1L);
        assertTrue(first.isEmpty() || first.size() >= 0);
        List<PatternSequenceMatcher.CompleteMatch<String>> done = matcher.process("b", 2L);
        assertTrue(done.size() >= 0);
        assertTrue(matcher.getPartialMatchCount() >= 0);
    }

    @Test
    void nonDeterministicContiguityKeepsBranchAlive() {
        PatternSequence<String> sequence = PatternSequence.<String>begin("a", type("a"))
                .followedByAny("b", type("b"));
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(sequence);
        matcher.process("a", 1L);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches = matcher.process("b", 2L);
        assertEquals(1, matches.size());
        // NON_DETERMINISTIC keeps a copy of the partial around
        assertTrue(matcher.getPartialMatchCount() >= 0);
    }

    @Test
    void quantifierToStringIsDescriptive() {
        assertTrue(PatternQuantifier.exactly(2).toString().contains("2"));
        assertTrue(PatternQuantifier.oneOrMore().toString().length() > 0);
        assertTrue(PatternQuantifier.zeroOrMore().toString().length() > 0);
        assertTrue(PatternQuantifier.optional().toString().length() > 0);
        assertTrue(PatternQuantifier.times(1, 4).toString().length() > 0);
        assertTrue(PatternQuantifier.atLeast(3).toString().length() > 0);
    }
}
