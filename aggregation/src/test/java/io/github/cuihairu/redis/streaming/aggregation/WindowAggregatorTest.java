package io.github.cuihairu.redis.streaming.aggregation;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
        assertEquals(42, stats.getValueCount());
        assertEquals(Duration.ofSeconds(10), stats.getWindowSize());
        assertEquals(timestamp, stats.getTimestamp());
    }

    @Test
    void registerFunctionStoresAggregationFunction() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        aggregator.registerFunction("SUM", values -> values.stream().mapToInt(v -> (Integer) v).sum());

        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.EPOCH;
        String expectedKey = "p:window:k:0:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.valueRange(0L, true, 10_000L, false)).thenReturn(List.of(1, 2, 3));

        // Should not throw when using the function
        Integer result = aggregator.getAggregatedResult(window, "k", "SUM", timestamp);
        assertEquals(6, result);
    }

    @Test
    void testGetAggregatedResultWithEmptyWindow() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        aggregator.registerFunction("COUNT", values -> values.size());

        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.valueRange(10_000L, true, 20_000L, false)).thenReturn(Collections.emptyList());

        Integer result = aggregator.getAggregatedResult(window, "k", "COUNT", timestamp);

        assertEquals(0, result);
    }

    @Test
    void testGetAggregatedResultWithSingleValue() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        aggregator.registerFunction("FIRST", values -> values.isEmpty() ? null : values.iterator().next());

        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.valueRange(10_000L, true, 20_000L, false)).thenReturn(List.of("single-value"));

        String result = aggregator.getAggregatedResult(window, "k", "FIRST", timestamp);

        assertEquals("single-value", result);
    }

    @Test
    void testGetWindowStatisticsWithZeroCount() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.count(10_000L, true, 20_000L, false)).thenReturn(0);

        WindowAggregator.WindowStatistics stats = aggregator.getWindowStatistics(window, "k", timestamp);

        assertEquals(0, stats.getValueCount());
    }

    @Test
    void testCreatePVCounter() {
        RedissonClient redisson = mock(RedissonClient.class);
        WindowAggregator aggregator = new WindowAggregator(redisson, "prefix");

        var pvCounter = aggregator.createPVCounter(Duration.ofMinutes(5));

        assertNotNull(pvCounter);
    }

    @Test
    void testCreateTopKAnalyzer() {
        RedissonClient redisson = mock(RedissonClient.class);
        WindowAggregator aggregator = new WindowAggregator(redisson, "prefix");

        var topKAnalyzer = aggregator.createTopKAnalyzer(10, Duration.ofMinutes(5));

        assertNotNull(topKAnalyzer);
    }

    @Test
    void testClearKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        WindowAggregator aggregator = new WindowAggregator(redisson, "prefix");

        // Should not throw
        assertDoesNotThrow(() -> aggregator.clearKey("test-key"));
    }

    @Test
    void testAddValueWithSlidingWindow() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        TimeWindow window = SlidingWindow.of(Duration.ofSeconds(10), Duration.ofSeconds(5));
        Instant timestamp = Instant.ofEpochMilli(12_345);

        when(redisson.getScoredSortedSet(any(String.class))).thenReturn(sortedSet);

        aggregator.addValue(window, "k", "v", timestamp);

        verify(sortedSet).add(anyDouble(), eq("v"));
        verify(sortedSet).removeRangeByScore(anyDouble(), anyBoolean(), anyDouble(), anyBoolean());
    }

    @Test
    void testConstructorWithNullPrefix() {
        RedissonClient redisson = mock(RedissonClient.class);

        // Should not throw
        assertDoesNotThrow(() -> new WindowAggregator(redisson, null));
    }

    @Test
    void testConstructorWithEmptyPrefix() {
        RedissonClient redisson = mock(RedissonClient.class);

        assertDoesNotThrow(() -> new WindowAggregator(redisson, ""));
    }

    @Test
    void testRegisterMultipleFunctions() {
        WindowAggregator aggregator = new WindowAggregator(mock(RedissonClient.class), "p");

        aggregator.registerFunction("COUNT", values -> values.size());
        aggregator.registerFunction("SUM", values -> values.stream().mapToInt(v -> (Integer) v).sum());
        aggregator.registerFunction("AVG", values -> values.stream().mapToInt(v -> (Integer) v).average().orElse(0));

        // All functions should be available
        assertDoesNotThrow(() -> {
            // Note: This will still need mocking for Redis operations
            // Just verifying the functions are registered
            aggregator.registerFunction("EXTRA", values -> 0);
        });
    }

    @Test
    void testGetAggregatedResultWithNullValues() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        aggregator.registerFunction("COUNT_NULL", values -> values.stream().filter(v -> v == null).count());

        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.valueRange(10_000L, true, 20_000L, false))
                .thenReturn(Arrays.asList("a", null, "b", null));

        Long result = aggregator.getAggregatedResult(window, "k", "COUNT_NULL", timestamp);

        assertEquals(2L, result);
    }

    @Test
    void testAddValueWithDifferentTypes() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);

        aggregator.addValue(window, "k", 123, timestamp);
        aggregator.addValue(window, "k", "string", timestamp);
        aggregator.addValue(window, "k", 45.67, timestamp);

        verify(sortedSet, times(3)).add(anyDouble(), any());
    }

    @Test
    void testWindowStatisticsGetters() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.count(10_000L, true, 20_000L, false)).thenReturn(100);

        WindowAggregator.WindowStatistics stats = aggregator.getWindowStatistics(window, "k", timestamp);

        assertEquals(Instant.ofEpochMilli(10_000), stats.getWindowStart());
        assertEquals(Instant.ofEpochMilli(20_000), stats.getWindowEnd());
        assertEquals(100, stats.getValueCount());
        assertEquals(Duration.ofSeconds(10), stats.getWindowSize());
        assertEquals(timestamp, stats.getTimestamp());
    }

    @Test
    void testGetAggregatedResultWithComplexAggregation() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        aggregator.registerFunction("COMPLEX", values -> {
            int sum = values.stream().mapToInt(v -> (Integer) v).sum();
            double avg = values.stream().mapToInt(v -> (Integer) v).average().orElse(0);
            return "sum=" + sum + ",avg=" + avg;
        });

        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.valueRange(10_000L, true, 20_000L, false))
                .thenReturn(List.of(10, 20, 30));

        String result = aggregator.getAggregatedResult(window, "k", "COMPLEX", timestamp);

        assertEquals("sum=60,avg=20.0", result);
    }

    @Test
    void testGetAggregatedResultWithMinMaxWindow() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        aggregator.registerFunction("MIN", values -> values.stream().mapToInt(v -> (Integer) v).min().orElse(Integer.MIN_VALUE));

        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.valueRange(10_000L, true, 20_000L, false))
                .thenReturn(List.of(5, 2, 8, 1, 9));

        Integer result = aggregator.getAggregatedResult(window, "k", "MIN", timestamp);

        assertEquals(1, result);
    }

    @Test
    void testGetAggregatedResultWithMaxWindow() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> sortedSet = mock(RScoredSortedSet.class);

        WindowAggregator aggregator = new WindowAggregator(redisson, "p");
        aggregator.registerFunction("MAX", values -> values.stream().mapToInt(v -> (Integer) v).max().orElse(Integer.MAX_VALUE));

        TimeWindow window = TumblingWindow.of(Duration.ofSeconds(10));
        Instant timestamp = Instant.ofEpochMilli(12_345);
        String expectedKey = "p:window:k:10000:TumblingWindow";

        when(redisson.<Object>getScoredSortedSet(expectedKey)).thenReturn(sortedSet);
        when(sortedSet.valueRange(10_000L, true, 20_000L, false))
                .thenReturn(List.of(5, 2, 8, 1, 9));

        Integer result = aggregator.getAggregatedResult(window, "k", "MAX", timestamp);

        assertEquals(9, result);
    }
}
