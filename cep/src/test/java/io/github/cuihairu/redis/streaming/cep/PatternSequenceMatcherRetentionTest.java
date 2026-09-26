package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for B-20: completeMatches used to grow without bound — cleanup only
 * ever removed expired partial matches, and getCompleteMatches() copied the whole
 * history on every call. A matcher under high match traffic therefore OOMed on long
 * runs even though process() already hands every match to the caller as it happens.
 *
 * <p>The matcher now retains only the most recent {@code maxRetainedMatches} complete
 * matches (default 1000).
 */
class PatternSequenceMatcherRetentionTest {

    /** A followedBy B: every A*,B# pair produces exactly one complete match. */
    private static PatternSequenceMatcher<String> newAbMatcher(int maxRetained) {
        PatternSequence<String> pattern = PatternSequence.<String>begin()
                .where("A", Pattern.of(s -> s.startsWith("A")))
                .followedBy("B", Pattern.of(s -> s.startsWith("B")));
        return new PatternSequenceMatcher<>(pattern, maxRetained);
    }

    /** Feed n complete A,B pairs at distinct timestamps; events carry marker suffixes. */
    private static void feedMatches(PatternSequenceMatcher<String> matcher, int n) {
        for (int i = 0; i < n; i++) {
            matcher.process("A" + i, 1000L + i * 10);
            matcher.process("B" + i, 1001L + i * 10);
        }
    }

    @Test
    void defaultRetentionIsBounded() {
        // one-arg ctor only: this test compiles against the pre-fix API, where the
        // history was unbounded and grew to the full 1005 fed matches
        PatternSequence<String> pattern = PatternSequence.<String>begin()
                .where("A", Pattern.of(s -> s.startsWith("A")))
                .followedBy("B", Pattern.of(s -> s.startsWith("B")));
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        feedMatches(matcher, 1005);
        assertTrue(matcher.getCompleteMatches().size() <= 1000,
                "complete match history must be bounded by default (old code: grew to "
                        + matcher.getCompleteMatches().size() + ")");
    }

    @Test
    void explicitCapRetainsNewestMatchesOnly() {
        PatternSequenceMatcher<String> matcher = newAbMatcher(3);

        feedMatches(matcher, 5);

        List<PatternSequenceMatcher.CompleteMatch<String>> retained = matcher.getCompleteMatches();
        assertEquals(3, retained.size(), "history must be capped at the configured size");
        // the three newest matches survive: the B events of pairs 2, 3, 4 (0-based)
        assertEquals("B2", retained.get(0).getEvents().get(1));
        assertEquals("B4", retained.get(2).getEvents().get(1));
    }

    @Test
    void zeroCapRetainsNothingButStillReturnsMatches() {
        PatternSequenceMatcher<String> matcher = newAbMatcher(0);

        List<PatternSequenceMatcher.CompleteMatch<String>> delivered = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            delivered.addAll(matcher.process("A" + i, 1000L + i * 10));
            delivered.addAll(matcher.process("B" + i, 1001L + i * 10));
        }

        assertEquals(3, delivered.size(), "process() must still deliver every match");
        assertTrue(matcher.getCompleteMatches().isEmpty(), "history must retain nothing at cap 0");
    }

    @Test
    void negativeCapRejected() {
        assertThrows(IllegalArgumentException.class, () -> newAbMatcher(-1));
    }

    @Test
    void defaultConstructorAppliesDefaultCap() {
        // existing two-arg... one-arg construction keeps working with the default cap
        PatternSequence<String> pattern = PatternSequence.<String>begin()
                .where("A", Pattern.of(s -> s.startsWith("A")))
                .followedBy("B", Pattern.of(s -> s.startsWith("B")));
        PatternSequenceMatcher<String> matcher = new PatternSequenceMatcher<>(pattern);

        feedMatches(matcher, 2);
        assertEquals(2, matcher.getCompleteMatches().size());
    }
}
