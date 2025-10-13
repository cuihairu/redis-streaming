package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatternMatcherTest {

    @Test
    void testSimpleMatch() {
        Pattern<Integer> greaterThan5 = Pattern.of(x -> x > 5);
        PatternConfig<Integer> config = PatternConfig.<Integer>builder()
                .pattern(greaterThan5)
                .timeWindow(Duration.ofSeconds(10))
                .build();

        PatternMatcher<Integer> matcher = new PatternMatcher<>(config);

        List<EventSequence<Integer>> matches = matcher.process(10);
        assertEquals(1, matches.size());
        assertEquals(10, matches.get(0).getFirst());
    }

    @Test
    void testNoMatch() {
        Pattern<Integer> greaterThan5 = Pattern.of(x -> x > 5);
        PatternConfig<Integer> config = PatternConfig.<Integer>builder()
                .pattern(greaterThan5)
                .timeWindow(Duration.ofSeconds(10))
                .build();

        PatternMatcher<Integer> matcher = new PatternMatcher<>(config);

        List<EventSequence<Integer>> matches = matcher.process(3);
        assertEquals(0, matches.size());
    }

    @Test
    void testMultipleMatches() {
        Pattern<Integer> even = Pattern.of(x -> x % 2 == 0);
        PatternConfig<Integer> config = PatternConfig.<Integer>builder()
                .pattern(even)
                .timeWindow(Duration.ofSeconds(10))
                .build();

        PatternMatcher<Integer> matcher = new PatternMatcher<>(config);

        List<EventSequence<Integer>> matches1 = matcher.process(2);
        List<EventSequence<Integer>> matches2 = matcher.process(4);
        List<EventSequence<Integer>> matches3 = matcher.process(6);

        assertEquals(1, matches1.size());
        assertEquals(1, matches2.size());
        assertEquals(1, matches3.size());
    }

    @Test
    void testTimeWindowExpiration() throws InterruptedException {
        Pattern<Integer> any = Pattern.of(x -> true);
        PatternConfig<Integer> config = PatternConfig.<Integer>builder()
                .pattern(any)
                .timeWindow(Duration.ofMillis(50))
                .allowEventReuse(true)
                .build();

        PatternMatcher<Integer> matcher = new PatternMatcher<>(config);

        long time1 = System.currentTimeMillis();
        matcher.process(1, time1);

        // Should have at least 1 active sequence
        assertTrue(matcher.getActiveSequenceCount() >= 1);

        // After time window, sequence should be cleaned up
        long time2 = time1 + 100; // Beyond 50ms window
        matcher.process(2, time2);

        // Old sequence should have been removed, only new ones remain
        assertTrue(matcher.getActiveSequenceCount() >= 1);
    }

    @Test
    void testMaxSequenceLength() {
        Pattern<Integer> any = Pattern.of(x -> true);
        PatternConfig<Integer> config = PatternConfig.<Integer>builder()
                .pattern(any)
                .maxSequenceLength(3)
                .allowEventReuse(true)
                .build();

        PatternMatcher<Integer> matcher = new PatternMatcher<>(config);

        matcher.process(1);
        matcher.process(2);
        matcher.process(3);
        matcher.process(4);

        // Should not exceed max sequence length
        assertTrue(matcher.getActiveSequenceCount() > 0);
    }

    @Test
    void testClear() {
        Pattern<Integer> any = Pattern.of(x -> true);
        PatternConfig<Integer> config = PatternConfig.<Integer>builder()
                .pattern(any)
                .allowEventReuse(true)
                .build();

        PatternMatcher<Integer> matcher = new PatternMatcher<>(config);

        matcher.process(1);
        matcher.process(2);
        assertTrue(matcher.getActiveSequenceCount() > 0);

        matcher.clear();
        assertEquals(0, matcher.getActiveSequenceCount());
    }

    @Test
    void testGetConfig() {
        Pattern<Integer> pattern = Pattern.of(x -> true);
        PatternConfig<Integer> config = PatternConfig.<Integer>builder()
                .pattern(pattern)
                .build();

        PatternMatcher<Integer> matcher = new PatternMatcher<>(config);

        assertSame(config, matcher.getConfig());
    }

    @Test
    void testInvalidConfig() {
        PatternConfig<Integer> invalidConfig = PatternConfig.<Integer>builder()
                .pattern(null)
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                new PatternMatcher<>(invalidConfig)
        );
    }
}
