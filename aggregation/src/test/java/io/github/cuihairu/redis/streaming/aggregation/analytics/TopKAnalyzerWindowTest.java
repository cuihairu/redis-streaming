package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Window-semantics tests for the bucketed TopKAnalyzer (B-18): {@code windowSize} governs
 * which buckets a query sums, contributions expire once their bucket leaves the trailing
 * window, and writes stamp a TTL so Redis reaps expired buckets.
 */
class TopKAnalyzerWindowTest {

    private static final long T0 = 60_000_000L; // inside bucket 1000 with 1-minute buckets

    private final AtomicLong now = new AtomicLong(T0);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final ConcurrentHashMap<String, RScoredSortedSet<String>> buckets = new ConcurrentHashMap<>();

    private TopKAnalyzer newAnalyzer(Duration windowSize) {
        when(redisson.<String>getScoredSortedSet(anyString())).thenAnswer(inv ->
                buckets.computeIfAbsent(inv.getArgument(0), key -> mock(RScoredSortedSet.class)));
        return new TopKAnalyzer(redisson, "p", 3, windowSize, now::get);
    }

    @SuppressWarnings("unchecked")
    private RScoredSortedSet<String> bucket(String key) {
        return (RScoredSortedSet<String>) buckets.computeIfAbsent(key, k -> mock(RScoredSortedSet.class));
    }

    @SafeVarargs
    private final void withEntries(RScoredSortedSet<String> bucket, ScoredEntry<String>... entries) {
        when(bucket.entryRangeReversed(0, Integer.MAX_VALUE)).thenReturn(List.of(entries));
    }

    @Test
    void contributionsExpireOnceTheirBucketLeavesTheTrailingWindow() {
        // 10-minute window => 1-minute buckets; T0 sits in bucket 1000
        TopKAnalyzer analyzer = newAnalyzer(Duration.ofMinutes(10));
        when(bucket("p:topk:pages:b:1000").getScore("a")).thenReturn(3.0);
        withEntries(bucket("p:topk:pages:b:1000"), new ScoredEntry<>(3.0, "a"));

        assertEquals(3.0, analyzer.getScore("pages", "a"));

        // advance past the window AND the bucket granularity: now = T0 + window + 1 bucket
        now.set(T0 + Duration.ofMinutes(10).toMillis() + 60_000L);

        assertEquals(0.0, analyzer.getScore("pages", "a"),
                "a bucket entirely behind the trailing window must not contribute");
        assertTrue(analyzer.getTopK("pages").isEmpty());
        assertEquals(-1, analyzer.getRank("pages", "a"));
    }

    @Test
    void bucketStillCountsWhileItOverlapsTheWindow() {
        TopKAnalyzer analyzer = newAnalyzer(Duration.ofMinutes(10));
        when(bucket("p:topk:pages:b:1000").getScore("a")).thenReturn(3.0);

        // half a window forward: bucket 1000 still overlaps the trailing window
        now.set(T0 + Duration.ofMinutes(5).toMillis());

        assertEquals(3.0, analyzer.getScore("pages", "a"),
                "a bucket overlapping the trailing window still contributes");
    }

    @Test
    void queryMergesAdjacentBucketsWithinTheWindow() {
        TopKAnalyzer analyzer = newAnalyzer(Duration.ofMinutes(10));
        // T0 sits in bucket 1000; bucket 999 is the adjacent older bucket, both live
        when(bucket("p:topk:pages:b:1000").getScore("a")).thenReturn(2.0);
        when(bucket("p:topk:pages:b:999").getScore("a")).thenReturn(3.0);

        assertEquals(5.0, analyzer.getScore("pages", "a"),
                "window score sums the item's scores across all live buckets");
    }

    @Test
    void writesStampTtlBeyondTheWindowAndTargetTheCurrentBucket() {
        TopKAnalyzer analyzer = newAnalyzer(Duration.ofMinutes(10));
        RScoredSortedSet<String> current = bucket("p:topk:pages:b:1000");
        when(current.addScore("a", 1.0)).thenReturn(1.0);
        when(current.size()).thenReturn(1);

        analyzer.recordItem("pages", "a", 1.0);

        verify(current).addScore("a", 1.0);
        Instant expectedExpiry = Instant.ofEpochMilli(T0 + Duration.ofMinutes(10).toMillis() + 120_000L);
        verify(current).expire(expectedExpiry);
    }

    @Test
    void bucketLengthIsAtLeastOneMillisecond() {
        // a 5ms window would yield a 0.5ms bucket; the floor keeps bucket math sound
        TopKAnalyzer analyzer = newAnalyzer(Duration.ofMillis(5));
        RScoredSortedSet<String> current = bucket("p:topk:pages:b:" + (T0)); // 1ms buckets
        when(current.addScore("a", 1.0)).thenReturn(1.0);
        when(current.size()).thenReturn(1);

        analyzer.recordItem("pages", "a", 1.0);
        verify(current).addScore("a", 1.0);
    }
}
