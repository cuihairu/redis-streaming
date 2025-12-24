package io.github.cuihairu.redis.streaming.aggregation;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WindowAggregatorTest {

    @Test
    void addValueStoresScoredValueAndCleansUpBeforeWindowStart() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);

        aggregator.addValue(window, "k", "v", timestamp);

        verify(sortedSet).add(12_345d, "v");
        verify(sortedSet).removeRangeByScore(0d, true, 10_000d, false);
    }

    @Test
    void getAggregatedResultUsesRegisteredFunctionAndWindowBounds() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        aggregator.registerFunction("COUNT", values -> values.size());

        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.valueRange(10_000L, true, 20_000L, false)).thenReturn(List.of("a", "b", "c"));

        Integer out = aggregator.getAggregatedResult(window, "k", "COUNT", timestamp);

        assertEquals(3, out);
        verify(sortedSet).valueRange(10_000L, true, 20_000L, false);
    }

    @Test
    void getAggregatedResultThrowsForUnknownFunction() {
        WindowAggregator aggregator = new WindowAggregator(mock(RedissonClient.class), "p");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                aggregator.getAggregatedResult(TumblingWindow.ofSeconds(10), "k", "MISSING", Instant.EPOCH));
        assertEquals("Unknown aggregation function: MISSING", ex.getMessage());
    }

    @Test
    void getWindowStatisticsReadsCountWithinWindow() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.count(10_000L, true, 20_000L, false)).thenReturn(42);

        WindowAggregator.WindowStatistics stats = aggregator.getWindowStatistics(window, "k", timestamp);

        assertNotNull(stats);
        assertEquals(Instant.ofEpochMilli(10_000), stats.getWindowStart());
        assertEquals(Instant.ofEpochMilli(20_000), stats.getWindowEnd());
        assertEquals(42L, stats.getValueCount());
        assertEquals(Duration.ofSeconds(10), stats.getWindowSize());
        assertEquals(timestamp, stats.getTimestamp());
    }
}
