package io.github.cuihairu.redis.streaming.aggregation;

import io.github.cuihairu.redis.streaming.aggregation.analytics.PVCounter;
import io.github.cuihairu.redis.streaming.aggregation.analytics.TopKAnalyzer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScoredSortedSet;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Window aggregator that performs aggregations over time windows using Redis data structures
 */
@Slf4j
public class WindowAggregator {

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final Map<String, AggregationFunction<?, ?>> functions = new ConcurrentHashMap<>();

    public WindowAggregator(RedissonClient redissonClient, String keyPrefix) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
    }

    /**
     * Register an aggregation function
     *
     * @param name the function name
     * @param function the aggregation function
     */
    public <T, R> void registerFunction(String name, AggregationFunction<T, R> function) {
        functions.put(name, function);
        log.info("Registered aggregation function: {}", name);
    }

    /**
     * Add a value to a windowed aggregation
     *
     * @param window the time window
     * @param key the aggregation key
     * @param value the value to add
     * @param timestamp the timestamp
     */
    public void addValue(TimeWindow window, String key, Object value, Instant timestamp) {
        String windowKey = getWindowKey(window, key, timestamp);
        RScoredSortedSet<Object> sortedSet = redissonClient.getScoredSortedSet(windowKey);

        // Use timestamp as score
        double score = timestamp.toEpochMilli();
        sortedSet.add(score, value);

        // Clean up old values outside the window
        Instant windowStart = window.getWindowStart(timestamp);
        double cutoffTime = windowStart.toEpochMilli();
        sortedSet.removeRangeByScore(0, true, cutoffTime, false);

        log.debug("Added value {} to window {} at {}", value, windowKey, timestamp);
    }

    /**
     * Get aggregated result for a window
     *
     * @param window the time window
     * @param key the aggregation key
     * @param functionName the aggregation function name
     * @param timestamp the current timestamp
     * @return the aggregated result
     */
    @SuppressWarnings("unchecked")
    public <R> R getAggregatedResult(TimeWindow window, String key, String functionName, Instant timestamp) {
        AggregationFunction<Object, R> function = (AggregationFunction<Object, R>) functions.get(functionName);
        if (function == null) {
            throw new IllegalArgumentException("Unknown aggregation function: " + functionName);
        }

        String windowKey = getWindowKey(window, key, timestamp);
        RScoredSortedSet<Object> sortedSet = redissonClient.getScoredSortedSet(windowKey);

        // Get window bounds
        Instant windowStart = window.getWindowStart(timestamp);
        Instant windowEnd = window.getWindowEnd(timestamp);

        // Get values within the window
        Collection<Object> values = sortedSet.valueRange(
                windowStart.toEpochMilli(), true,
                windowEnd.toEpochMilli(), false);

        return function.apply(values);
    }

    /**
     * Create a PV counter for the specified window size
     *
     * @param windowSize the window size
     * @return PV counter instance
     */
    public PVCounter createPVCounter(Duration windowSize) {
        return new PVCounter(redissonClient, keyPrefix, windowSize);
    }

    /**
     * Create a Top-K analyzer
     *
     * @param k the number of top items to track
     * @param windowSize the window size
     * @return Top-K analyzer instance
     */
    public TopKAnalyzer createTopKAnalyzer(int k, Duration windowSize) {
        return new TopKAnalyzer(redissonClient, keyPrefix, k, windowSize);
    }

    /**
     * Get window statistics
     *
     * @param window the time window
     * @param key the aggregation key
     * @param timestamp the current timestamp
     * @return window statistics
     */
    public WindowStatistics getWindowStatistics(TimeWindow window, String key, Instant timestamp) {
        String windowKey = getWindowKey(window, key, timestamp);
        RScoredSortedSet<Object> sortedSet = redissonClient.getScoredSortedSet(windowKey);

        Instant windowStart = window.getWindowStart(timestamp);
        Instant windowEnd = window.getWindowEnd(timestamp);

        long count = sortedSet.count(
                windowStart.toEpochMilli(), true,
                windowEnd.toEpochMilli(), false);

        return new WindowStatistics(
                windowStart,
                windowEnd,
                count,
                window.getSize(),
                timestamp
        );
    }

    /**
     * Clear all data for a specific aggregation key
     *
     * @param key the aggregation key
     */
    public void clearKey(String key) {
        // This would require tracking all window keys for a given key
        // Implementation depends on the specific use case
        log.info("Clearing data for key: {}", key);
    }

    private String getWindowKey(TimeWindow window, String key, Instant timestamp) {
        Instant windowStart = window.getWindowStart(timestamp);
        return String.format("%s:window:%s:%d:%s",
                keyPrefix, key, windowStart.toEpochMilli(), window.getClass().getSimpleName());
    }

    /**
     * Window statistics data class
     */
    @Data
    @AllArgsConstructor
    public static class WindowStatistics {
        private final Instant windowStart;
        private final Instant windowEnd;
        private final long valueCount;
        private final Duration windowSize;
        private final Instant timestamp;
    }
}