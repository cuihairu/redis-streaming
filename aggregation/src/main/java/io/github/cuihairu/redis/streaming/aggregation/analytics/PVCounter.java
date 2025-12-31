package io.github.cuihairu.redis.streaming.aggregation.analytics;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private final String pagesIndexKey;

    public PVCounter(RedissonClient redissonClient, String keyPrefix, Duration windowSize) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
        this.windowSize = windowSize;
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);
        this.pagesIndexKey = keyPrefix + ":pv:pages";

        // Schedule cleanup of old data
        long intervalMs = Math.max(1000L, windowSize.toMillis());
        this.cleanupExecutor.scheduleWithFixedDelay(
                this::cleanupExpiredData,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
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
        if (page == null || page.isBlank()) {
            return 0L;
        }
        Instant ts = timestamp != null ? timestamp : Instant.now();
        String key = getKeyForPage(page);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);
        getPagesIndex().add(page);

        // Use timestamp as score, and a unique ID as value
        double score = ts.toEpochMilli();
        String value = ts.toEpochMilli() + "-" + System.nanoTime();

        sortedSet.add(score, value);

        // Remove old entries outside the window
        double cutoffTime = Instant.now().minus(windowSize).toEpochMilli();
        sortedSet.removeRangeByScore(0, true, cutoffTime, true);

        long count = sortedSet.size();
        log.debug("Recorded PV for page '{}' at {}, current count: {}", page, ts, count);

        return count;
    }

    /**
     * Get the current page view count for a specific page
     *
     * @param page the page identifier
     * @return the current count
     */
    public long getPageViewCount(String page) {
        if (page == null || page.isBlank()) {
            return 0L;
        }
        String key = getKeyForPage(page);
        RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);
        getPagesIndex().add(page);

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
        if (page == null || page.isBlank() || start == null || end == null || !end.isAfter(start)) {
            return 0L;
        }
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
        if (page == null || page.isBlank()) {
            return;
        }
        String key = getKeyForPage(page);
        redissonClient.getScoredSortedSet(key).clear();
        getPagesIndex().remove(page);
        log.info("Reset PV count for page '{}'", page);
    }

    /**
     * Get statistics for all pages
     *
     * @return PV statistics
     */
    public PVStatistics getStatistics() {
        RSet<String> pages = getPagesIndex();
        List<String> snapshot = new ArrayList<>(pages.readAll());
        long totalViews = 0L;
        for (String page : snapshot) {
            try {
                totalViews += getPageViewCount(page);
            } catch (Exception e) {
                log.debug("Failed to compute PV for page {}", page, e);
            }
        }
        return new PVStatistics(snapshot.size(), totalViews, Instant.now());
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
        try {
            double cutoffTime = Instant.now().minus(windowSize).toEpochMilli();
            RSet<String> pages = getPagesIndex();
            for (String page : pages.readAll()) {
                String key = getKeyForPage(page);
                RScoredSortedSet<String> sortedSet = redissonClient.getScoredSortedSet(key);
                try {
                    sortedSet.removeRangeByScore(0, true, cutoffTime, true);
                } catch (Exception ignore) {}
                try {
                    if (sortedSet.size() == 0) {
                        pages.remove(page);
                    }
                } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            log.debug("Cleanup expired PV data failed", e);
        }
    }

    private RSet<String> getPagesIndex() {
        return redissonClient.getSet(pagesIndexKey);
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
