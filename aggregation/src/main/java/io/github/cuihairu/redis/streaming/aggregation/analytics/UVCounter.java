package io.github.cuihairu.redis.streaming.aggregation.analytics;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RHyperLogLog;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Unique Visitor (UV) counter using Redis HyperLogLog.
 *
 * <p>Implements a rolling window via time-bucketed HyperLogLog keys and unions them using
 * {@link RHyperLogLog#countWith(String...)}.</p>
 */
@Slf4j
public class UVCounter implements AutoCloseable {

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final Duration windowSize;
    private final Duration bucketSize;
    private final ScheduledExecutorService cleanupExecutor;
    private final String pagesIndexKey;

    public UVCounter(RedissonClient redissonClient, String keyPrefix, Duration windowSize) {
        this(redissonClient, keyPrefix, windowSize, Duration.ofMinutes(1));
    }

    public UVCounter(RedissonClient redissonClient, String keyPrefix, Duration windowSize, Duration bucketSize) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
        this.windowSize = windowSize;
        this.bucketSize = bucketSize == null || bucketSize.isZero() || bucketSize.isNegative()
                ? Duration.ofMinutes(1)
                : bucketSize;
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);
        this.pagesIndexKey = keyPrefix + ":uv:pages";

        long intervalMs = Math.max(1000L, windowSize.toMillis());
        this.cleanupExecutor.scheduleWithFixedDelay(
                this::cleanupExpiredData,
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Record a visit.
     *
     * @return true if the visitor was newly added to the bucket
     */
    public boolean add(String page, String visitorId, Instant timestamp) {
        if (page == null || page.isBlank() || visitorId == null || visitorId.isBlank()) {
            return false;
        }
        Instant ts = timestamp != null ? timestamp : Instant.now();
        String bucketKey = getBucketKey(page, ts);
        RHyperLogLog<String> hll = redissonClient.getHyperLogLog(bucketKey);
        boolean added = hll.add(visitorId);

        // Keep buckets slightly longer than the window to avoid edge truncation.
        try {
            hll.expire(windowSize.plus(bucketSize).plus(bucketSize));
        } catch (Exception ignore) {}

        getPagesIndex().add(page);
        getBucketIndex(page).add(bucketKey);
        try {
            getBucketIndex(page).expire(windowSize.plus(bucketSize).plus(bucketSize));
        } catch (Exception ignore) {}

        return added;
    }

    /**
     * Count unique visitors for the rolling window ending at "now".
     */
    public long count(String page) {
        return count(page, Instant.now());
    }

    /**
     * Count unique visitors for the rolling window ending at {@code now}.
     */
    public long count(String page, Instant now) {
        if (page == null || page.isBlank()) {
            return 0L;
        }
        Instant end = now != null ? now : Instant.now();
        Instant start = end.minus(windowSize);
        List<String> keys = bucketKeysForRange(page, start, end);
        if (keys.isEmpty()) {
            return 0L;
        }

        String first = keys.get(0);
        RHyperLogLog<String> base = redissonClient.getHyperLogLog(first);
        if (keys.size() == 1) {
            return base.count();
        }
        String[] others = keys.subList(1, keys.size()).toArray(new String[0]);
        return base.countWith(others);
    }

    /**
     * Count unique visitors for a time range (bucket-aligned approximation).
     */
    public long count(String page, Instant start, Instant end) {
        if (page == null || page.isBlank() || start == null || end == null || !end.isAfter(start)) {
            return 0L;
        }
        List<String> keys = bucketKeysForRange(page, start, end);
        if (keys.isEmpty()) {
            return 0L;
        }
        String first = keys.get(0);
        RHyperLogLog<String> base = redissonClient.getHyperLogLog(first);
        if (keys.size() == 1) {
            return base.count();
        }
        return base.countWith(keys.subList(1, keys.size()).toArray(new String[0]));
    }

    /**
     * Reset all UV data for a page (best-effort).
     */
    public void reset(String page) {
        if (page == null || page.isBlank()) {
            return;
        }
        try {
            RSet<String> bucketIndex = getBucketIndex(page);
            Set<String> snapshot = bucketIndex.readAll();
            for (String key : snapshot) {
                try {
                    redissonClient.getKeys().delete(key);
                } catch (Exception ignore) {}
            }
            bucketIndex.clear();
            getPagesIndex().remove(page);
        } catch (Exception e) {
            log.debug("Reset UV failed for page {}", page, e);
        }
    }

    @Override
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
    }

    private String getBucketKey(String page, Instant timestamp) {
        long bucketMillis = bucketSize.toMillis();
        long ts = timestamp.toEpochMilli();
        long bucketStart = (ts / bucketMillis) * bucketMillis;
        return keyPrefix + ":uv:" + page + ":" + bucketStart;
    }

    private RSet<String> getPagesIndex() {
        return redissonClient.getSet(pagesIndexKey);
    }

    private RSet<String> getBucketIndex(String page) {
        return redissonClient.getSet(keyPrefix + ":uv:" + page + ":buckets");
    }

    private List<String> bucketKeysForRange(String page, Instant start, Instant end) {
        long bucketMillis = bucketSize.toMillis();
        long from = start.toEpochMilli();
        long to = end.toEpochMilli();
        long firstBucket = (from / bucketMillis) * bucketMillis;
        long lastBucket = (to / bucketMillis) * bucketMillis;

        int maxBuckets = (int) Math.min(10_000, (lastBucket - firstBucket) / bucketMillis + 1);
        List<String> keys = new ArrayList<>(Math.max(0, maxBuckets));
        long cur = firstBucket;
        for (int i = 0; i < maxBuckets && cur <= lastBucket; i++) {
            keys.add(keyPrefix + ":uv:" + page + ":" + cur);
            cur += bucketMillis;
        }
        return keys;
    }

    private void cleanupExpiredData() {
        try {
            Instant now = Instant.now();
            Instant start = now.minus(windowSize);
            long bucketMillis = bucketSize.toMillis();
            long cutoffBucket = (start.toEpochMilli() / bucketMillis) * bucketMillis;

            RSet<String> pages = getPagesIndex();
            for (String page : pages.readAll()) {
                RSet<String> bucketIdx = getBucketIndex(page);
                boolean anyAlive = false;
                for (String key : bucketIdx.readAll()) {
                    Long ts = parseBucketStart(key);
                    if (ts == null) {
                        continue;
                    }
                    if (ts < cutoffBucket) {
                        bucketIdx.remove(key);
                        continue;
                    }
                    anyAlive = true;
                }
                if (!anyAlive && bucketIdx.size() == 0) {
                    pages.remove(page);
                }
            }
        } catch (Exception e) {
            log.debug("Cleanup expired UV data failed", e);
        }
    }

    private Long parseBucketStart(String key) {
        if (key == null) return null;
        int idx = key.lastIndexOf(':');
        if (idx < 0 || idx == key.length() - 1) return null;
        try {
            return Long.parseLong(key.substring(idx + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
