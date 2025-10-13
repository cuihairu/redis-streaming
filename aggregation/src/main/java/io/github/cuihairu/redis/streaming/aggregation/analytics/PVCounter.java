package io.github.cuihairu.redis.streaming.aggregation.analytics;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScoredSortedSet;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Page View (PV) counter using Redis Sorted Sets for time-based aggregation
 */
@Slf4j
public class PVCounter {

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final Duration windowSize;
    private final ScheduledExecutorService cleanupExecutor;

    public PVCounter(RedissonClient redissonClient, String keyPrefix, Duration windowSize) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
        this.windowSize = windowSize;
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);

        // Schedule cleanup of old data
        this.cleanupExecutor.scheduleWithFixedDelay(
                this::cleanupExpiredData,
                windowSize.toMinutes(),
                windowSize.toMinutes(),
                TimeUnit.MINUTES
        );
    }

    /**
     * Record a page view for a specific page
     *
     * @param page the page identifier
     * @return the current count for this page
     */
    public long recordPageView(String page) {
        return recordPageView(page, Instant.now());
    }

    /**
     * Record a page view for a specific page at a given time
     *
     * @param page the page identifier
     * @param timestamp the timestamp of the page view
     * @return the current count for this page
     */
    public long recordPageView(String page, Instant timestamp) {
        String key = getKeyForPage(page);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);

        // Use timestamp as score, and a unique ID as value
        double score = timestamp.toEpochMilli();
        String value = timestamp.toEpochMilli() + "-" + System.nanoTime();

        sortedSet.add(score, value);

        // Remove old entries outside the window
        double cutoffTime = Instant.now().minus(windowSize).toEpochMilli();
        sortedSet.removeRangeByScore(0, true, cutoffTime, true);

        long count = sortedSet.size();
        log.debug("Recorded PV for page '{}' at {}, current count: {}", page, timestamp, count);

        return count;
    }

    /**
     * Get the current page view count for a specific page
     *
     * @param page the page identifier
     * @return the current count
     */
    public long getPageViewCount(String page) {
        String key = getKeyForPage(page);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);

        // Remove old entries outside the window
        double cutoffTime = Instant.now().minus(windowSize).toEpochMilli();
        sortedSet.removeRangeByScore(0, true, cutoffTime, true);

        return sortedSet.size();
    }

    /**
     * Get page view count for a specific time range
     *
     * @param page the page identifier
     * @param start start time (inclusive)
     * @param end end time (exclusive)
     * @return the count within the time range
     */
    public long getPageViewCount(String page, Instant start, Instant end) {
        String key = getKeyForPage(page);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);

        return sortedSet.count(start.toEpochMilli(), true, end.toEpochMilli(), false);
    }

    /**
     * Reset the count for a specific page
     *
     * @param page the page identifier
     */
    public void resetPageViewCount(String page) {
        String key = getKeyForPage(page);
        redissonClient.getScoredSortedSet(key).clear();
        log.info("Reset PV count for page '{}'", page);
    }

    /**
     * Get statistics for all pages
     *
     * @return PV statistics
     */
    public PVStatistics getStatistics() {
        // This is a simplified implementation
        // In a real scenario, you'd want to track all pages in a separate set
        return new PVStatistics(0, 0, Instant.now());
    }

    /**
     * Close the PV counter and cleanup resources
     */
    public void close() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("PV counter closed");
    }

    private String getKeyForPage(String page) {
        return keyPrefix + ":pv:" + page;
    }

    private void cleanupExpiredData() {
        log.debug("Cleaning up expired PV data older than {}", windowSize);
        // This would be implemented with a more sophisticated approach
        // tracking all active keys and cleaning them up
    }

    /**
     * PV statistics data class
     */
    @Data
    @AllArgsConstructor
    public static class PVStatistics {
        private final long totalPages;
        private final long totalViews;
        private final Instant timestamp;
    }
}