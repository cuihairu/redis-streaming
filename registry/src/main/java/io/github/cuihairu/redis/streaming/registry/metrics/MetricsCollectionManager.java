package io.github.cuihairu.redis.streaming.registry.metrics;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 指标收集管理器
 */
public class MetricsCollectionManager {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCollectionManager.class);

    private final List<MetricCollector> collectors;
    private final MetricsConfig config;
    private final Map<String, Object> lastMetrics = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCollectionTimes = new ConcurrentHashMap<>();

    public MetricsCollectionManager(List<MetricCollector> collectors, MetricsConfig config) {
        this.collectors = collectors != null ? collectors : Collections.emptyList();
        this.config = config != null ? config : new MetricsConfig();
    }

    /**
     * 收集当前应该更新的指标
     */
    public Map<String, Object> collectMetrics(boolean forceAll) {
        long now = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();

        for (MetricCollector collector : collectors) {
            String metricType = collector.getMetricType();

            // 检查是否启用
            if (!config.getEnabledMetrics().contains(metricType)) {
                continue;
            }

            // 检查是否需要收集
            if (!forceAll && !shouldCollectMetric(metricType, now)) {
                // 使用缓存的值
                Object lastValue = lastMetrics.get(metricType);
                if (lastValue != null) {
                    result.put(metricType, lastValue);
                }
                continue;
            }

            // 收集新指标
            try {
                Object metric = collectWithTimeout(collector);
                if (metric != null) {
                    result.put(metricType, metric);
                    lastMetrics.put(metricType, metric);
                    lastCollectionTimes.put(metricType, now);
                }
            } catch (Exception e) {
                logger.warn("Failed to collect metric: " + metricType, e);
            }
        }

        return result;
    }

    private boolean shouldCollectMetric(String metricType, long now) {
        Long lastTime = lastCollectionTimes.get(metricType);
        if (lastTime == null) {
            return true; // 首次收集
        }

        Duration interval = config.getCollectionIntervals()
                .getOrDefault(metricType, config.getDefaultCollectionInterval());

        return now - lastTime >= interval.toMillis();
    }

    private Object collectWithTimeout(MetricCollector collector) throws Exception {
        if (!collector.isAvailable()) {
            return null;
        }

        // 使用 CompletableFuture 实现超时控制
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            try {
                return collector.collectMetric();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        return future.get(config.getCollectionTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 检查指标是否有显著变化
     */
    public boolean hasSignificantChange(Map<String, Object> newMetrics) {
        if (!config.isImmediateUpdateOnSignificantChange()) {
            return false;
        }

        for (Map.Entry<String, ChangeThreshold> entry : config.getChangeThresholds().entrySet()) {
            String path = entry.getKey();
            ChangeThreshold threshold = entry.getValue();

            Object newValue = getNestedValue(newMetrics, path);
            Object oldValue = getNestedValue(lastMetrics, path);

            if (threshold.isSignificant(oldValue, newValue)) {
                logger.debug("Significant change detected for {}: {} -> {}", path, oldValue, newValue);
                return true;
            }
        }

        return false;
    }

    private Object getNestedValue(Map<String, Object> map, String path) {
        String[] parts = path.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * 获取最后收集的指标
     */
    public Map<String, Object> getLastMetrics() {
        return new HashMap<>(lastMetrics);
    }

    /**
     * 清除指标缓存
     */
    public void clearCache() {
        lastMetrics.clear();
        lastCollectionTimes.clear();
    }
}