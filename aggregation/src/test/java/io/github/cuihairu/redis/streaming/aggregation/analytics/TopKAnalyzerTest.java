package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the bucketed windowed TopKAnalyzer (B-18). Buckets are keyed
 * {@code <prefix>:topk:<category>:b:<index>}; the analyzer sums the trailing window's
 * buckets, so these tests stub per-bucket sorted sets selected by key.
 */
class TopKAnalyzerTest {

    /** 10-minute window, 1-minute buckets, fixed "now" = 1,000,000 -> live buckets 6..16. */
    private static final long NOW = 1_000_000L;
    private static final String CURRENT_BUCKET_KEY = "p:topk:pages:b:16";
    private static final String OLDER_BUCKET_KEY = "p:topk:pages:b:6";

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

    @SafeVarargs
    private final void withEntries(RScoredSortedSet<String> bucket, ScoredEntry<String>... entries) {
        Collection<ScoredEntry<String>> asList = List.of(entries);
        when(bucket.entryRangeReversed(0, Integer.MAX_VALUE)).thenReturn(asList);
    }

    private static ScoredEntry<String> entry(String item, double score) {
        return new ScoredEntry<>(score, item);
    }

    @Test
    void recordItemAddsScoreAndOptionallyTrims() {
        TopKAnalyzer analyzer = newAnalyzer(4);
        RScoredSortedSet<String> current = bucket(CURRENT_BUCKET_KEY);

        when(current.addScore("home", 2.0)).thenReturn(7.0);
        when(current.size()).thenReturn(9); // k*2 = 8, triggers trim

        double score = analyzer.recordItem("pages", "home", 2.0);
        assertEquals(7.0, score);

        verify(current).removeRangeByRank(0, 1);
        verify(current).expire(any(Instant.class));
    }

    @Test
    void getTopKReturnsItemsWithScores() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        withEntries(bucket(CURRENT_BUCKET_KEY), entry("a", 2.0), entry("b", 0.5));

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).getItem());
        assertEquals(2.0, items.get(0).getScore());
        assertNotNull(items.get(0).getTimestamp());

        assertEquals("b", items.get(1).getItem());
        assertEquals(0.5, items.get(1).getScore());
    }

    @Test
    void getRankIsOneBasedAndMissingIsMinusOne() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        withEntries(bucket(CURRENT_BUCKET_KEY), entry("a", 3.0), entry("b", 1.0));

        assertEquals(1, analyzer.getRank("pages", "a"));
        assertEquals(2, analyzer.getRank("pages", "b"));
        assertEquals(-1, analyzer.getRank("pages", "missing"));
    }

    @Test
    void getScoreFallsBackToZero() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        when(bucket(CURRENT_BUCKET_KEY).getScore("a")).thenReturn(1.5);

        assertEquals(1.5, analyzer.getScore("pages", "a"));
        assertEquals(0.0, analyzer.getScore("pages", "missing"),
                "missing in every live bucket must fall back to 0.0");
    }

    @Test
    void getScoreSumsAcrossWindowBuckets() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        when(bucket(CURRENT_BUCKET_KEY).getScore("a")).thenReturn(100.5);
        when(bucket(OLDER_BUCKET_KEY).getScore("a")).thenReturn(25.75);

        assertEquals(126.25, analyzer.getScore("pages", "a"),
                "an item's window score is the sum of its bucket scores");
    }

    @Test
    void resetDeletesEveryLiveWindowBucket() {
        TopKAnalyzer analyzer = newAnalyzer(3);

        analyzer.reset("pages");

        bucketsByKey.values().forEach(bucketMock -> verify(bucketMock).delete());
        // 11 buckets cover a 10-minute window of 1-minute buckets
        assertEquals(11, bucketsByKey.size());
    }

    @Test
    void recordItemDoesNotTrimWhenBelowThreshold() {
        TopKAnalyzer analyzer = newAnalyzer(4);
        RScoredSortedSet<String> current = bucket(CURRENT_BUCKET_KEY);

        when(current.addScore("home", 2.0)).thenReturn(5.0);
        when(current.size()).thenReturn(5); // k*2 = 8, does not trigger trim

        double score = analyzer.recordItem("pages", "home", 2.0);
        assertEquals(5.0, score);

        verify(current, never()).removeRangeByRank(anyInt(), anyInt());
    }

    @Test
    void getTopKReturnsEmptyListWhenNoItems() {
        TopKAnalyzer analyzer = newAnalyzer(3);

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");
        assertTrue(items.isEmpty());
    }

    @Test
    void recordItemWithNegativeScore() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        RScoredSortedSet<String> current = bucket(CURRENT_BUCKET_KEY);
        when(current.addScore("home", -5.0)).thenReturn(-3.0);

        assertEquals(-3.0, analyzer.recordItem("pages", "home", -5.0));
    }

    @Test
    void recordItemWithZeroScore() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        RScoredSortedSet<String> current = bucket(CURRENT_BUCKET_KEY);
        when(current.addScore("home", 0.0)).thenReturn(0.0);

        assertEquals(0.0, analyzer.recordItem("pages", "home", 0.0));
    }

    @Test
    void getRankReturnsOneForTopItem() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        withEntries(bucket(CURRENT_BUCKET_KEY), entry("top", 5.0));

        assertEquals(1, analyzer.getRank("pages", "top"));
    }

    @Test
    void getRankForMultipleItems() {
        TopKAnalyzer analyzer = newAnalyzer(5);
        withEntries(bucket(CURRENT_BUCKET_KEY),
                entry("first", 10.0), entry("second", 8.0), entry("third", 6.0), entry("fourth", 4.0));

        assertEquals(1, analyzer.getRank("pages", "first"));
        assertEquals(2, analyzer.getRank("pages", "second"));
        assertEquals(3, analyzer.getRank("pages", "third"));
        assertEquals(4, analyzer.getRank("pages", "fourth"));
    }

    @Test
    void getTopKWithFullResults() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        withEntries(bucket(CURRENT_BUCKET_KEY), entry("a", 10.0), entry("b", 5.0), entry("c", 1.0));

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");
        assertEquals(3, items.size());
        assertEquals(10.0, items.get(0).getScore());
        assertEquals(5.0, items.get(1).getScore());
        assertEquals(1.0, items.get(2).getScore());
    }

    @Test
    void getTopKMergesScoresAcrossBuckets() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        withEntries(bucket(CURRENT_BUCKET_KEY), entry("a", 10.0), entry("b", 5.0));
        withEntries(bucket(OLDER_BUCKET_KEY), entry("b", 6.0));

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");
        assertEquals(2, items.size());
        assertEquals("b", items.get(0).getItem(),
                "b's combined 11.0 must outrank a's 10.0 (old code could not see this merge)");
        assertEquals(11.0, items.get(0).getScore());
        assertEquals(10.0, items.get(1).getScore());
    }

    @Test
    void recordItemWithLargeScore() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        RScoredSortedSet<String> current = bucket(CURRENT_BUCKET_KEY);
        when(current.addScore("viral", 1000000.0)).thenReturn(1000000.0);

        assertEquals(1000000.0, analyzer.recordItem("pages", "viral", 1000000.0));
    }

    @Test
    void recordItemWithFractionalScore() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        RScoredSortedSet<String> current = bucket(CURRENT_BUCKET_KEY);
        when(current.addScore("item", 3.14159)).thenReturn(6.28318);

        assertEquals(6.28318, analyzer.recordItem("pages", "item", 3.14159));
    }

    @Test
    void getTopKTimestampIsNotNull() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        withEntries(bucket(CURRENT_BUCKET_KEY), entry("x", 1.0), entry("y", 2.0));

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");
        assertNotNull(items.get(0).getTimestamp());
        assertNotNull(items.get(1).getTimestamp());
    }

    @Test
    void multipleCategories() {
        TopKAnalyzer analyzer = newAnalyzer(3);
        RScoredSortedSet<String> pages = bucket("p:topk:pages:b:16");
        RScoredSortedSet<String> products = bucket("p:topk:products:b:16");

        when(pages.addScore("home", 1.0)).thenReturn(1.0);
        when(products.addScore("widget", 1.0)).thenReturn(1.0);

        analyzer.recordItem("pages", "home", 1.0);
        analyzer.recordItem("products", "widget", 1.0);

        verify(pages).addScore("home", 1.0);
        verify(products).addScore("widget", 1.0);
    }

    @Test
    void getTopKItemToString() {
        TopKAnalyzer.TopKItem item = new TopKAnalyzer.TopKItem("test", 5.0, Instant.ofEpochMilli(123456789L));
        String str = item.toString();

        assertTrue(str.contains("test"));
        assertTrue(str.contains("5.0"));
    }

    @Test
    void invalidConstructionIsRejected() {
        RedissonClient client = mock(RedissonClient.class);
        assertThrows(NullPointerException.class, () -> new TopKAnalyzer(null, "p", 3, Duration.ofMinutes(1)));
        assertThrows(NullPointerException.class, () -> new TopKAnalyzer(client, null, 3, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new TopKAnalyzer(client, "p", 0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new TopKAnalyzer(client, "p", 3, null));
        assertThrows(IllegalArgumentException.class, () -> new TopKAnalyzer(client, "p", 3, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TopKAnalyzer(client, "p", 3, Duration.ofMillis(-5)));
    }
}
