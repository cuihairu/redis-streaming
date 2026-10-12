package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the partial-match snapshot round trip used by the operator state migration
 * ({@code cep.operator}): export → (persist / restart) → restore must continue matching
 * with the exact same event order.
 */
class PatternSequenceMatcherStateExportTest {

    private static Pattern<String> type(String t) {
        return Pattern.of(t::equals);
    }

    /** Three-step strict sequence: "a" then "b" then "c". */
    private static PatternSequence<String> abcSequence() {
        return PatternSequence.<String>begin("s1", type("a"))
                .next("s2", type("b"))
                .next("s3", type("c"))
                .within(Duration.ofSeconds(10));
    }

    @Test
    void exportRestoreRoundTripContinuesMatchingWithSameEventOrder() {
        PatternSequenceMatcher<String> source = new PatternSequenceMatcher<>(abcSequence(), 0);
        assertTrue(source.process("a", 100).isEmpty());
        assertTrue(source.process("b", 200).isEmpty());
        assertEquals(1, source.getPartialMatchCount());
        List<PatternSequenceMatcher.PartialMatchState<String>> snapshot = source.exportPartialMatches();

        // restart: a fresh matcher resumes from the snapshot and completes the sequence
        PatternSequenceMatcher<String> resumed = new PatternSequenceMatcher<>(abcSequence(), 0);
        resumed.restorePartialMatches(snapshot);
        List<PatternSequenceMatcher.CompleteMatch<String>> matches = resumed.process("c", 300);

        assertEquals(1, matches.size());
        assertEquals(List.of("a", "b", "c"), matches.get(0).getEvents());
        assertEquals(100, matches.get(0).getStartTimestamp());
        assertEquals(300, matches.get(0).getEndTimestamp());
    }

    @Test
    void exportPreservesPerStepEventLists() {
        PatternSequenceMatcher<String> source = new PatternSequenceMatcher<>(abcSequence(), 0);
        assertTrue(source.process("a", 100).isEmpty());
        assertTrue(source.process("b", 200).isEmpty());

        List<PatternSequenceMatcher.PartialMatchState<String>> snapshot = source.exportPartialMatches();
        assertEquals(1, snapshot.size());
        PatternSequenceMatcher.PartialMatchState<String> state = snapshot.get(0);
        assertEquals(100, state.getStartTimestamp());
        assertEquals(2, state.getCurrentStep());
        assertEquals(Map.of(0, List.of("a"), 1, List.of("b")), state.getEventsByStep());
    }

    @Test
    void restoreReplacesExistingPartials() {
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(abcSequence(), 0);
        matcher.process("a", 100);

        matcher.restorePartialMatches(List.of());
        assertEquals(0, matcher.getPartialMatchCount());

        PatternSequenceMatcher.PartialMatchState<String> state = new PatternSequenceMatcher.PartialMatchState<>();
        state.setStartTimestamp(500);
        state.setCurrentStep(2);
        state.setEventsByStep(Map.of(0, List.of("a"), 1, List.of("b")));
        matcher.restorePartialMatches(List.of(state));
        assertEquals(1, matcher.getPartialMatchCount());
    }

    @Test
    void restoreRejectsCorruptSnapshots() {
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(abcSequence(), 0);

        PatternSequenceMatcher.PartialMatchState<String> negativeStep =
                new PatternSequenceMatcher.PartialMatchState<>();
        negativeStep.setStartTimestamp(1);
        negativeStep.setCurrentStep(1);
        negativeStep.setEventsByStep(Map.of(-1, List.of("a")));
        assertThrows(IllegalArgumentException.class, () -> matcher.restorePartialMatches(List.of(negativeStep)));

        PatternSequenceMatcher.PartialMatchState<String> nullEvents =
                new PatternSequenceMatcher.PartialMatchState<>();
        nullEvents.setStartTimestamp(1);
        nullEvents.setCurrentStep(1);
        assertThrows(IllegalArgumentException.class, () -> matcher.restorePartialMatches(List.of(nullEvents)));

        // List.of forbids nulls (NPE at construction); a null state inside the list is what
        // restore itself must reject
        assertThrows(IllegalArgumentException.class,
                () -> matcher.restorePartialMatches(Collections.singletonList(null)));
    }

    @Test
    void exportOfFreshMatcherIsEmpty() {
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(abcSequence(), 0);
        assertTrue(matcher.exportPartialMatches().isEmpty());
        matcher.restorePartialMatches(null);
        assertTrue(matcher.exportPartialMatches().isEmpty());
    }
}
