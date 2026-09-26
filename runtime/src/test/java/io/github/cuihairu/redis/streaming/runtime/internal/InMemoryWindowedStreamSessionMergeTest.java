package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.assigners.SessionWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for session-window merging (B-01): before the fix the engine bucketed each
 * element under its own {@code [ts, ts+gap)} window with no coalescing, so a single session
 * produced one result per element (count=1 each) instead of one merged result per session.
 */
class InMemoryWindowedStreamSessionMergeTest {

    private static final long GAP = 100L;

    private static List<String> run(SessionWindow<Integer> assigner, List<KeyedRecord<String, Integer>> records) {
        List<String> results = new ArrayList<>();
        new InMemoryWindowedStream<>(records::iterator, assigner)
                .apply((key, window, elements, out) -> {
                    long count = 0;
                    for (Integer ignored : elements) {
                        count++;
                    }
                    out.collect(key + "[" + window.getStart() + "," + window.getEnd() + ")=" + count);
                })
                .addSink(result -> results.add((String) result));
        return results;
    }

    @Test
    void contiguousEventsWithinGapMergeIntoOneSessionResult() {
        List<String> results = run(
                SessionWindow.withGapMillis(GAP),
                List.of(
                        new KeyedRecord<>("k", 1, 0L),
                        new KeyedRecord<>("k", 2, 50L),
                        new KeyedRecord<>("k", 3, 120L),
                        new KeyedRecord<>("k", 4, 170L)
                ));

        // One merged session [0,270) holding all four elements.
        assertEquals(List.of("k[0,270)=4"), results);
    }

    @Test
    void inactivityBeyondTheGapSplitsSessions() {
        List<String> results = run(
                SessionWindow.withGapMillis(GAP),
                List.of(
                        new KeyedRecord<>("k", 1, 0L),
                        new KeyedRecord<>("k", 2, 50L),
                        new KeyedRecord<>("k", 3, 200L)
                ));

        assertEquals(List.of("k[0,150)=2", "k[200,300)=1"), results);
    }

    @Test
    void inactivityOfExactlyTheGapSplitsSessions() {
        // Windows are half-open: [0,100) and [100,200) touch but do not overlap.
        List<String> results = run(
                SessionWindow.withGapMillis(GAP),
                List.of(
                        new KeyedRecord<>("k", 1, 0L),
                        new KeyedRecord<>("k", 2, 100L)
                ));

        assertEquals(List.of("k[0,100)=1", "k[100,200)=1"), results);
    }

    @Test
    void disjointSessionsAreBridgedByALaterOverlappingEvent() {
        // 0 → [0,100); 250 → [250,350); 120 → [120,220) — all disjoint so far.
        // 90 → [90,190) bridges [0,100) and [120,220) into [0,220);
        // 210 → [210,310) then bridges that with [250,350) into [0,350).
        List<String> results = run(
                SessionWindow.withGapMillis(GAP),
                List.of(
                        new KeyedRecord<>("k", 1, 0L),
                        new KeyedRecord<>("k", 2, 250L),
                        new KeyedRecord<>("k", 3, 120L),
                        new KeyedRecord<>("k", 4, 90L),
                        new KeyedRecord<>("k", 5, 210L)
                ));

        assertEquals(List.of("k[0,350)=5"), results);
    }

    @Test
    void keysMergeIndependently() {
        List<String> results = run(
                SessionWindow.withGapMillis(GAP),
                List.of(
                        new KeyedRecord<>("a", 1, 0L),
                        new KeyedRecord<>("b", 1, 10L),
                        new KeyedRecord<>("a", 2, 60L),
                        new KeyedRecord<>("b", 2, 500L),
                        new KeyedRecord<>("a", 3, 120L)
                ));

        // a's session merges once more when its third element arrives, which re-inserts the
        // merged bucket at the end of the emission order (b's buckets fire first).
        assertEquals(List.of("b[10,110)=1", "b[500,600)=1", "a[0,220)=3"), results);
    }
}
