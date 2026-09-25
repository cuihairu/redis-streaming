package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the residual {@link PatternSequenceMatcher} branches: the
 * {@code consumeEventAtFirstMatch} loop-exit path taken when every remaining
 * step is optional and unmatched, and the max-occurrence rejection path of a
 * {@code {0,0}} step that observes its forbidden pattern.
 */
class PatternSequenceMatcherResidualCoverageTest {

    private static Pattern<String> type(String type) {
        return event -> type.equals(event);
    }

    @Test
    void unmatchedEventWalksPastAllOptionalStepsWithoutFailing() {
        PatternSequence<String> sequence = PatternSequence.<String>begin("maybeA", type("a"))
                .optional()
                .followedBy("maybeB", type("b"))
                .optional();
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(sequence);

        List<PatternSequenceMatcher.CompleteMatch<String>> out = matcher.process("zz", 1L);

        assertTrue(out.isEmpty(), "an event matching no optional step must not produce a match");
        assertEquals(0, matcher.getPartialMatchCount(), "no partial may survive a fully skipped walk");
        assertTrue(matcher.getCompleteMatches().isEmpty());
    }

    @Test
    void zeroOccurrenceStepRejectsEventsMatchingItsPattern() {
        PatternSequence<String> sequence = PatternSequence.<String>begin("neverA", type("a"))
                .times(0, 0);
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(sequence);

        List<PatternSequenceMatcher.CompleteMatch<String>> out = matcher.process("a", 1L);

        assertTrue(out.isEmpty(), "a {0,0} step must reject events that match its pattern");
        assertEquals(0, matcher.getPartialMatchCount());
    }

    @Test
    void zeroOccurrenceStepStillSkipsForOtherEvents() {
        PatternSequence<String> sequence = PatternSequence.<String>begin("neverA", type("a"))
                .times(0, 0)
                .followedBy("thenB", type("b"));
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(sequence);

        assertTrue(matcher.process("c", 1L).isEmpty(),
                "an unrelated event skips the forbidden step and dies on the required step");

        List<PatternSequenceMatcher.CompleteMatch<String>> out = matcher.process("b", 2L);
        assertEquals(1, out.size(), "the forbidden step is skipped for unrelated events");
    }
}
