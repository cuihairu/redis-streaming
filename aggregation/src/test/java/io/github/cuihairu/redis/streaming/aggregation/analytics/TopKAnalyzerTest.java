package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopKAnalyzerTest {

    @Test
    void recordItemAddsScoreAndOptionallyTrims() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 4, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.addScore("home", 2.0)).thenReturn(7.0);
        when(sortedSet.size()).thenReturn(9); // k*2 = 8, triggers trim

        double score = analyzer.recordItem("pages", "home", 2.0);
        assertEquals(7.0, score);

        verify(sortedSet).removeRangeByRank(0, 1);
    }

    @Test
    void getTopKReturnsItemsWithScores() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.valueRangeReversed(0, 2)).thenReturn(List.of("a", "b"));
        when(sortedSet.getScore("a")).thenReturn(2.0);
        when(sortedSet.getScore("b")).thenReturn(null);

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).getItem());
        assertEquals(2.0, items.get(0).getScore());
        assertNotNull(items.get(0).getTimestamp());

        assertEquals("b", items.get(1).getItem());
        assertEquals(0.0, items.get(1).getScore());
    }

    @Test
    void getRankIsOneBasedAndMissingIsMinusOne() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.revRank("a")).thenReturn(0);
        when(sortedSet.revRank("missing")).thenReturn(null);

        assertEquals(1, analyzer.getRank("pages", "a"));
        assertEquals(-1, analyzer.getRank("pages", "missing"));
    }

    @Test
    void getScoreFallsBackToZero() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.getScore("a")).thenReturn(1.5);
        when(sortedSet.getScore("missing")).thenReturn(null);

        assertEquals(1.5, analyzer.getScore("pages", "a"));
        assertEquals(0.0, analyzer.getScore("pages", "missing"));
    }

    @Test
    void resetDelegatesToClear() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        analyzer.reset("pages");

        verify(sortedSet).clear();
    }

    @Test
    void recordItemDoesNotTrimWhenBelowThreshold() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 4, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.addScore("home", 2.0)).thenReturn(5.0);
        when(sortedSet.size()).thenReturn(5); // k*2 = 8, does not trigger trim

        double score = analyzer.recordItem("pages", "home", 2.0);
        assertEquals(5.0, score);

        // Should not trim
        verify(sortedSet).addScore("home", 2.0);
    }

    @Test
    void getTopKReturnsEmptyListWhenNoItems() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.valueRangeReversed(0, 2)).thenReturn(List.of());

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");
        assertTrue(items.isEmpty());
    }

    @Test
    void recordItemWithNegativeScore() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.addScore("home", -5.0)).thenReturn(-3.0);

        double score = analyzer.recordItem("pages", "home", -5.0);
        assertEquals(-3.0, score);
    }

    @Test
    void recordItemWithZeroScore() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.addScore("home", 0.0)).thenReturn(0.0);

        double score = analyzer.recordItem("pages", "home", 0.0);
        assertEquals(0.0, score);
    }

    @Test
    void getRankReturnsOneForTopItem() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.revRank("top")).thenReturn(0);

        assertEquals(1, analyzer.getRank("pages", "top"));
    }

    @Test
    void getRankForMultipleItems() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 5, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.revRank("first")).thenReturn(0);
        when(sortedSet.revRank("second")).thenReturn(1);
        when(sortedSet.revRank("third")).thenReturn(2);
        when(sortedSet.revRank("fourth")).thenReturn(3);

        assertEquals(1, analyzer.getRank("pages", "first"));
        assertEquals(2, analyzer.getRank("pages", "second"));
        assertEquals(3, analyzer.getRank("pages", "third"));
        assertEquals(4, analyzer.getRank("pages", "fourth"));
    }

    @Test
    void getScoreForMultipleItems() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.getScore("item1")).thenReturn(100.5);
        when(sortedSet.getScore("item2")).thenReturn(50.0);
        when(sortedSet.getScore("item3")).thenReturn(25.75);

        assertEquals(100.5, analyzer.getScore("pages", "item1"));
        assertEquals(50.0, analyzer.getScore("pages", "item2"));
        assertEquals(25.75, analyzer.getScore("pages", "item3"));
    }

    @Test
    void getTopKWithFullResults() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.valueRangeReversed(0, 2)).thenReturn(List.of("a", "b", "c"));
        when(sortedSet.getScore("a")).thenReturn(10.0);
        when(sortedSet.getScore("b")).thenReturn(5.0);
        when(sortedSet.getScore("c")).thenReturn(1.0);

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");
        assertEquals(3, items.size());
        assertEquals(10.0, items.get(0).getScore());
        assertEquals(5.0, items.get(1).getScore());
        assertEquals(1.0, items.get(2).getScore());
    }

    @Test
    void recordItemWithLargeScore() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.addScore("viral", 1000000.0)).thenReturn(1000000.0);

        double score = analyzer.recordItem("pages", "viral", 1000000.0);
        assertEquals(1000000.0, score);
    }

    @Test
    void recordItemWithFractionalScore() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.addScore("item", 3.14159)).thenReturn(6.28318);

        double score = analyzer.recordItem("pages", "item", 3.14159);
        assertEquals(6.28318, score);
    }

    @Test
    void getTopKTimestampIsNotNull() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        when(sortedSet.valueRangeReversed(0, 2)).thenReturn(List.of("x", "y"));
        when(sortedSet.getScore("x")).thenReturn(1.0);
        when(sortedSet.getScore("y")).thenReturn(2.0);

        List<TopKAnalyzer.TopKItem> items = analyzer.getTopK("pages");
        assertNotNull(items.get(0).getTimestamp());
        assertNotNull(items.get(1).getTimestamp());
    }

    @Test
    void resetClearsAllData() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet);

        analyzer.reset("pages");

        verify(sortedSet).clear();
    }

    @Test
    void multipleCategories() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet1 = mock(RScoredSortedSet.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> sortedSet2 = mock(RScoredSortedSet.class);

        TopKAnalyzer analyzer = new TopKAnalyzer(redisson, "p", 3, Duration.ofMinutes(10));
        when(redisson.<String>getScoredSortedSet("p:topk:pages")).thenReturn(sortedSet1);
        when(redisson.<String>getScoredSortedSet("p:topk:products")).thenReturn(sortedSet2);

        when(sortedSet1.addScore("home", 1.0)).thenReturn(1.0);
        when(sortedSet2.addScore("widget", 1.0)).thenReturn(1.0);

        analyzer.recordItem("pages", "home", 1.0);
        analyzer.recordItem("products", "widget", 1.0);

        verify(sortedSet1).addScore("home", 1.0);
        verify(sortedSet2).addScore("widget", 1.0);
    }

    @Test
    void getTopKItemToString() {
        TopKAnalyzer.TopKItem item = new TopKAnalyzer.TopKItem("test", 5.0, Instant.ofEpochMilli(123456789L));
        String str = item.toString();

        assertTrue(str.contains("test"));
        assertTrue(str.contains("5.0"));
    }
}
