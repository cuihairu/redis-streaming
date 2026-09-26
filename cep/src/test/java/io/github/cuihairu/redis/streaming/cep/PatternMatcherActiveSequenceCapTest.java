package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for B-10: with {@code allowEventReuse(true)} every matching event
 * cloned all active sequences and added a new one, so the partial-sequence count doubled
 * per event (2^N) and OOMed after roughly 30 events of a fast stream — the time window
 * could not keep up. The matcher now retains at most {@code maxActiveSequences} partial
 * sequences (default 1000, newest first); completed output is unchanged.
 */
class PatternMatcherActiveSequenceCapTest {

    private static final long BASE_TS = 1000L;

    private static PatternConfig<Integer> reuseConfig() {
        return PatternConfig.<Integer>builder()
                .pattern(Pattern.of(x -> true))
                .allowEventReuse(true)
                .timeWindow(Duration.ofSeconds(10))
                .build();
    }

    /** Feeds n matching events inside the time window; returns total completed matches. */
    private static int feed(PatternMatcher<Integer> matcher, int n) {
        int completed = 0;
        for (int i = 0; i < n; i++) {
            List<EventSequence<Integer>> matches = matcher.process(1, BASE_TS + i * 10);
            completed += matches.size();
        }
        return completed;
    }

    @Test
    void defaultCapBoundsActiveSequences() {
        // one-arg ctor: default retention applies
        PatternMatcher<Integer> matcher = new PatternMatcher<>(reuseConfig());

        feed(matcher, 20);

        assertTrue(matcher.getActiveSequenceCount() <= PatternMatcher.DEFAULT_MAX_ACTIVE_SEQUENCES,
                "active sequences must be bounded by the default cap (old code: 2097150)");
        assertEquals(PatternMatcher.DEFAULT_MAX_ACTIVE_SEQUENCES, matcher.getActiveSequenceCount(),
                "a saturated matcher must hold exactly the cap");
    }

    @Test
    void explicitCapRetainsNewestSequencesOnly() {
        PatternMatcher<Integer> matcher = new PatternMatcher<>(reuseConfig(), 3);

        feed(matcher, 10);

        assertEquals(3, matcher.getActiveSequenceCount(), "history must be capped at the configured size");
    }

    @Test
    void zeroCapDisablesExtensionTrackingButKeepsCompletedOutput() {
        PatternMatcher<Integer> matcher = new PatternMatcher<>(reuseConfig(), 0);

        int completed = feed(matcher, 5);

        assertEquals(0, matcher.getActiveSequenceCount(), "cap 0 must retain no partial sequences");
        assertEquals(5, completed, "completed output must be unaffected by extension tracking");
    }

    @Test
    void negativeCapRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PatternMatcher<>(reuseConfig(), -1));
    }

    @Test
    void reuseDisabledStillProducesOneCompletedMatchPerEvent() {
        PatternConfig<Integer> config = PatternConfig.<Integer>builder()
                .pattern(Pattern.of(x -> true))
                .allowEventReuse(false)
                .timeWindow(Duration.ofSeconds(10))
                .build();
        PatternMatcher<Integer> matcher = new PatternMatcher<>(config);

        int completed = feed(matcher, 5);

        assertEquals(0, matcher.getActiveSequenceCount(),
                "without event reuse no partial sequences are tracked");
        assertEquals(5, completed);
    }

    @Test
    void timeWindowStillEvictsCappedSequences() {
        PatternMatcher<Integer> matcher = new PatternMatcher<>(reuseConfig(), 3);

        feed(matcher, 10);
        // an event beyond the 10s window expires every retained sequence; the fresh event
        // then yields exactly two partials (its own singleton plus one extension of it)
        matcher.process(1, BASE_TS + 60_000);

        assertEquals(2, matcher.getActiveSequenceCount(),
                "after expiry only the new event's sequences remain");
    }
}
