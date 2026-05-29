package io.github.cuihairu.redis.streaming.registry.metrics;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metric collection manager
 */
public class MetricsCollectionManager {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCollectionManager.class);

    private final List<MetricCollector> collectors;
    private final MetricsConfig config;
    private final Map<String, Object> lastMetrics = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCollectionTimes = new ConcurrentHashMap<>();

    private volatile boolean legacyWarnLogged = false;

    public MetricsCollectionManager(List<MetricCollector> collectors, MetricsConfig config) {
        this.collectors = collectors != null ? collectors : Collections.emptyList();
        this.config = config != null ? config : new MetricsConfig();
    }

    /**
     * Collect metrics that should be updated currently
     */
    public Map<String, Object> collectMetrics(boolean forceAll) {
        long now = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();

        for (MetricCollector collector : collectors) {
            String metricType = collector.getMetricType();

            // Check whether enabled
            if (!config.getEnabledMetrics().contains(metricType)) {
                continue;
            }

            // Check whether collection is needed
            if (!forceAll && !shouldCollectMetric(metricType, now)) {
                // Use cached value, but still flatten and export dotted/legacy keys for stable reporting
                Object lastValue = lastMetrics.get(metricType);
                if (lastValue != null) {
                    result.put(metricType, lastValue);
                    addDottedAndLegacyKeys(metricType, lastValue, result);
                }
                continue;
            }

            // Collect new metrics
            try {
                Object metric = collectWithTimeout(collector);
                if (metric != null) {
                    result.put(metricType, metric);
                    // Flatten to dotted keys (e.g., cpu.processCpuLoad) and dual-write legacy keys during transition
                    addDottedAndLegacyKeys(metricType, metric, result);
                    lastMetrics.put(metricType, metric);
                    lastCollectionTimes.put(metricType, now);
                }
            } catch (Exception e) {
                logger.warn("Failed to collect metric: " + metricType, e);
            }
        }

        return result;
    }

    /**
     * Flatten grouped metrics (e.g., {processCpuLoad: 0.2}) to the top level of result (e.g., cpu.processCpuLoad -> 0.2),
     * and synchronously write legacy keys (uppercase style) during the transition period for consumer compatibility.
     */
    @SuppressWarnings("unchecked")
    private void addDottedAndLegacyKeys(String metricType, Object metric, Map<String, Object> out) {
        if (!(metric instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) metric;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String leaf = e.getKey();
            Object val = e.getValue();
            // If the sub-key is already in dotted form (contains a dot), use it directly; otherwise prefix with metricType.
            String dotted = leaf.contains(".") ? leaf : (metricType + "." + leaf);
            out.put(dotted, val);
            // Legacy key mapping (limited set)
            String legacy = legacyKeyOf(dotted);
            if (legacy != null) {
                out.put(legacy, val);
                if (!legacyWarnLogged) {
                    legacyWarnLogged = true;
                    logger.warn("[metrics] Writing legacy metric keys for compatibility; will be removed in next minor release.");
                }
            }
        }
    }

    /**
     * Map dotted key -> legacy uppercase key name (only common fields maintained)
     * Note: This method accesses @Deprecated legacy keys for transition-period dual-write;
     * both this method and the dual-write logic will be removed in the next minor version.
     */
    @SuppressWarnings("deprecation")
    private String legacyKeyOf(String dotted) {
        return switch (dotted) {
            case MetricKeys.CPU_PROCESS_LOAD -> MetricKeys.LEGACY_CPU_PROCESS_LOAD;
            case MetricKeys.CPU_SYSTEM_LOAD -> MetricKeys.LEGACY_CPU_SYSTEM_LOAD;
            case MetricKeys.CPU_AVAILABLE_PROCESSORS -> MetricKeys.LEGACY_CPU_AVAILABLE_PROCESSORS;
            case MetricKeys.MEMORY_HEAP_USED -> MetricKeys.LEGACY_MEMORY_HEAP_USED;
            case MetricKeys.MEMORY_HEAP_MAX -> MetricKeys.LEGACY_MEMORY_HEAP_MAX;
            case MetricKeys.DISK_TOTAL_SPACE -> MetricKeys.LEGACY_DISK_TOTAL_SPACE;
            case MetricKeys.DISK_USED_SPACE  -> MetricKeys.LEGACY_DISK_USED_SPACE;
            case MetricKeys.DISK_FREE_SPACE  -> MetricKeys.LEGACY_DISK_FREE_SPACE;
            default -> null;
        };
    }

    private boolean shouldCollectMetric(String metricType, long now) {
        Long lastTime = lastCollectionTimes.get(metricType);
        if (lastTime == null) {
            return true; // First collection
        }

        Duration interval = config.getCollectionIntervals()
                .getOrDefault(metricType, config.getDefaultCollectionInterval());

        return now - lastTime >= interval.toMillis();
    }

    private Object collectWithTimeout(MetricCollector collector) throws Exception {
        if (!collector.isAvailable()) {
            return null;
        }

        // Use CompletableFuture for timeout control
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
     * Check whether metrics have significant changes
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
     * Get last collected metrics
     */
    public Map<String, Object> getLastMetrics() {
        return new HashMap<>(lastMetrics);
    }

    /**
     * Clear metric cache
     */
    public void clearCache() {
        lastMetrics.clear();
        lastCollectionTimes.clear();
    }
}
