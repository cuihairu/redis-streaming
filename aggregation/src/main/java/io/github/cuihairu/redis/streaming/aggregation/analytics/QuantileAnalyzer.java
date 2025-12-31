package io.github.cuihairu.redis.streaming.aggregation.analytics;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Quantile analyzer for rolling time windows.
 *
 * <p>Stores observations in two sorted sets per metric:
 * <ul>
 *   <li>time index: score=timestampMillis, value=observationId</li>
 *   <li>value index: score=value, value=observationId</li>
 * </ul>
 * Cleanup removes expired observationIds from both indices.</p>
 */
@Slf4j
public class QuantileAnalyzer implements AutoCloseable {

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final Duration windowSize;
    private final ScheduledExecutorService cleanupExecutor;
    private final String metricsIndexKey;

    public QuantileAnalyzer(RedissonClient redissonClient, String keyPrefix, Duration windowSize) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
        this.windowSize = windowSize;
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);
        this.metricsIndexKey = keyPrefix + ":quantile:metrics";

        long intervalMs = Math.max(1000L, windowSize.toMillis());
        this.cleanupExecutor.scheduleWithFixedDelay(this::cleanupExpiredData, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record a numeric observation for a metric.
     */
    public void record(String metric, double value) {
        record(metric, value, Instant.now());
    }

    /**
     * Record a numeric observation for a metric at a specific time.
     */
    public void record(String metric, double value, Instant timestamp) {
        if (metric == null || metric.isBlank()) {
            return;
        }
        Instant ts = timestamp != null ? timestamp : Instant.now();
        String id = ts.toEpochMilli() + "-" + UUID.randomUUID();

        RScoredSortedSet<String> timeIndex = getTimeIndex(metric);
        RScoredSortedSet<String> valueIndex = getValueIndex(metric);
        getMetricsIndex().add(metric);

        timeIndex.add(ts.toEpochMilli(), id);
        valueIndex.add(value, id);
    }

    /**
     * Compute quantile for the rolling window ending at now.
     *
     * @param q quantile in [0,1], e.g. 0.5 (p50), 0.95 (p95)
     * @return quantile value, or null if no samples
     */
    public Double quantile(String metric, double q) {
        cleanupExpiredMetric(metric);
        if (metric == null || metric.isBlank()) {
            return null;
        }
        double qq = Math.max(0.0, Math.min(1.0, q));
        RScoredSortedSet<String> valueIndex = getValueIndex(metric);
        int n = valueIndex.size();
        if (n <= 0) {
            return null;
        }
        int rank = (int) Math.floor(qq * (n - 1));
        Collection<ScoredEntry<String>> entries = valueIndex.entryRange(rank, rank);
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        ScoredEntry<String> e = entries.iterator().next();
        return e.getScore();
    }

    public Double p50(String metric) {
        return quantile(metric, 0.50);
    }

    public Double p95(String metric) {
        return quantile(metric, 0.95);
    }

    public Double p99(String metric) {
        return quantile(metric, 0.99);
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

    private void cleanupExpiredData() {
        try {
            RSet<String> metrics = getMetricsIndex();
            for (String metric : new ArrayList<>(metrics)) {
                cleanupExpiredMetric(metric);
            }
        } catch (Exception e) {
            log.debug("Quantile cleanup failed", e);
        }
    }

    private void cleanupExpiredMetric(String metric) {
        if (metric == null || metric.isBlank()) {
            return;
        }
        try {
            long cutoff = Instant.now().minus(windowSize).toEpochMilli();
            RScoredSortedSet<String> timeIndex = getTimeIndex(metric);
            RScoredSortedSet<String> valueIndex = getValueIndex(metric);

            Collection<String> expired = timeIndex.valueRange(0d, true, (double) cutoff, true);
            if (expired != null && !expired.isEmpty()) {
                // Remove from both indices (best-effort)
                try {
                    timeIndex.removeAll(expired);
                } catch (Exception ignore) {}
                try {
                    valueIndex.removeAll(expired);
                } catch (Exception ignore) {}
            }

            // Drop metric from index if empty
            if (timeIndex.size() == 0 && valueIndex.size() == 0) {
                getMetricsIndex().remove(metric);
            }
        } catch (Exception e) {
            log.debug("Quantile cleanup failed for metric {}", metric, e);
        }
    }

    private RScoredSortedSet<String> getTimeIndex(String metric) {
        return redissonClient.getScoredSortedSet(keyPrefix + ":quantile:" + metric + ":ts");
    }

    private RScoredSortedSet<String> getValueIndex(String metric) {
        return redissonClient.getScoredSortedSet(keyPrefix + ":quantile:" + metric + ":v");
    }

    private RSet<String> getMetricsIndex() {
        return redissonClient.getSet(metricsIndexKey);
    }
}

