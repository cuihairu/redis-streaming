package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-40 regression: ties must resolve deterministically everywhere. Queries rank
 * equal-scored items by name ascending; rank trimming evicts the tail of that same
 * order. The old code trimmed by Redis's rank order (ties lexicographically
 * ASCENDING), evicting exactly the tied item the queries rank first, while query
 * tie order was the map's iteration order.
 */
class TopKAnalyzerTieOrderTest {

    /** 10-minute window, 1-minute buckets, fixed "now" = 1,000,000 -> live buckets 6..16. */
    private static final long NOW = 1_000_000L;

    private RedissonClient redisson;
    private Map<String, RScoredSortedSet<String>> bucketsByKey;

    private TopKAnalyzer newAnalyzer(int k) {
        redisson = mock(RedissonClient.class);
        bucketsByKey = new ConcurrentHashMap<>();
        when(redisson.<String>getScoredSortedSet(anyString())).thenAnswer(inv ->
                bucketsByKey.computeIfAbsent(inv.getArgument(0), key -> mock(RScoredSortedSet.class)));
        return new TopKAnalyzer(redisson, "p", k, Duration.ofMinutes(10), () -> NOW);
    }

    @SuppressWarnings("unchecked")
    private RScoredSortedSet<String> bucket(String key) {
        return (RScoredSortedSet<String>) bucketsByKey.computeIfAbsent(key, k -> mock(RScoredSortedSet.class));
    }

    private static ScoredEntry<String> entry(String item, double score) {
        return new ScoredEntry<>(score, item);
    }

    @Test
    void trimEvictsTheQueryOrderTailNotTheLexicographicHead() {
        TopKAnalyzer analyzer = newAnalyzer(2);
        RScoredSortedSet<String> current = bucket("p:topk:pages:b:16");

        when(current.addScore("x", 1.0)).thenReturn(1.0);
        when(current.size()).thenReturn(6); // k*2 = 4 -> evict 2
        // Redis rank order (score asc, ties lex asc): m, a(2), b(2), x, y, z —
        // the cut at excess=2 slices through the {a,b} tie group
        when(current.entryRange(0, 1)).thenReturn(List.of(entry("m", 1.0), entry("a", 2.0)));
        when(current.entryRange(2.0, true, 2.0, true))
                .thenReturn(List.of(entry("a", 2.0), entry("b", 2.0)));

        analyzer.recordItem("pages", "x", 1.0);

        // eviction = (score asc, item desc): "m" (score 1) then "b" — "b" ranks below
        // "a" in queries, so it is the tied item that must go; the old rank-order trim
        // removed "a" instead
        verify(current).remove("m");
        verify(current).remove("b");
        verify(current, never()).remove("a");
    }

    @Test
    void equalTotalsRankAlphabeticallyAcrossBuckets() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        // pins the name-ascending tie contract regardless of the totals map's
        // iteration order (which the old code's tie order silently depended on)
        withEntries(bucket("p:topk:pages:b:6"), entry("q", 2.0));
        withEntries(bucket("p:topk:pages:b:16"), entry("a", 2.0));

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");

        assertEquals(2, items.size());
        assertEquals("a", items.get(0).getItem(),
                "equal totals must rank by name ascending, not by map iteration order");
        assertEquals("q", items.get(1).getItem());
        assertEquals(2.0, items.get(0).getScore());
        assertEquals(2.0, items.get(1).getScore());
    }

    @Test
    void equalTotalsRankAlphabeticallyForRanks() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        withEntries(bucket("p:topk:pages:b:16"), entry("q", 2.0), entry("a", 2.0));

        assertEquals(1, analyzer.getRank("pages", "a"),
                "'a' outranks 'q' at equal score (name-ascending tie policy)");
        assertEquals(2, analyzer.getRank("pages", "q"));
    }

    private void withEntries(RScoredSortedSet<String> bucket, ScoredEntry<String>... entries) {
        when(bucket.entryRangeReversed(0, Integer.MAX_VALUE)).thenReturn(List.of(entries));
    }
}
