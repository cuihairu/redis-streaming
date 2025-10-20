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

    private volatile boolean legacyWarnLogged = false;

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
                    // 统一：扁平化为 dotted key（如 cpu.processCpuLoad），并做过渡期旧键双写
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
     * 将分组的指标（如 {processCpuLoad: 0.2}）扁平化到 result 顶层（如 cpu.processCpuLoad -> 0.2），
     * 并在过渡期同步写入旧键（大写风格）以便消费端兼容。
     */
    @SuppressWarnings("unchecked")
    private void addDottedAndLegacyKeys(String metricType, Object metric, Map<String, Object> out) {
        if (!(metric instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) metric;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String leaf = e.getKey();
            Object val = e.getValue();
            // 如果子键已经是 dotted 形式（包含点），直接使用；否则前缀 metricType.
            String dotted = leaf.contains(".") ? leaf : (metricType + "." + leaf);
            out.put(dotted, val);
            // 旧键映射（有限集合）
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
     * Map dotted key -> 旧版大写键名（仅维护常见字段）
     * 说明：这里访问了被 @Deprecated 标注的旧键，以实现过渡期双写；
     * 下个小版本会移除此方法与双写逻辑。
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
