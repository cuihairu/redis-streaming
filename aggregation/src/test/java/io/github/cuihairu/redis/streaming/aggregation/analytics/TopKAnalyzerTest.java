package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
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
}
