package io.github.cuihairu.redis.streaming.registry.metrics;

import java.time.Duration;
import java.util.*;
import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThresholdType;

/**
 * 指标收集配置
 */
public class MetricsConfig {

    // 启用的指标类型
    private Set<String> enabledMetrics = Set.of("memory", "cpu", "application");

    // 各指标的收集间隔（覆盖默认值）
    private Map<String, Duration> collectionIntervals = Map.of(
            "memory", Duration.ofSeconds(30),
            "cpu", Duration.ofSeconds(60),
            "disk", Duration.ofMinutes(5),
            "network", Duration.ofMinutes(10)
    );

    // 指标变化阈值
    private Map<String, ChangeThreshold> changeThresholds = Map.of(
            MetricKeys.MEMORY_HEAP_USAGE_PERCENT, new ChangeThreshold(10.0, ChangeThresholdType.ABSOLUTE),
            MetricKeys.CPU_PROCESS_LOAD, new ChangeThreshold(0.20, ChangeThresholdType.ABSOLUTE),
            MetricKeys.DISK_USAGE_PERCENT, new ChangeThreshold(5.0, ChangeThresholdType.ABSOLUTE)
    );

    // 默认收集间隔
    private Duration defaultCollectionInterval = Duration.ofMinutes(1);

    // 是否在变化显著时立即更新
    private boolean immediateUpdateOnSignificantChange = true;

    // 收集超时时间
    private Duration collectionTimeout = Duration.ofSeconds(5);

    public Set<String> getEnabledMetrics() {
        return enabledMetrics;
    }

    public void setEnabledMetrics(Set<String> enabledMetrics) {
        this.enabledMetrics = enabledMetrics;
    }

    public Map<String, Duration> getCollectionIntervals() {
        return collectionIntervals;
    }

    public void setCollectionIntervals(Map<String, Duration> collectionIntervals) {
        this.collectionIntervals = collectionIntervals;
    }

    public Map<String, ChangeThreshold> getChangeThresholds() {
        return changeThresholds;
    }

    public void setChangeThresholds(Map<String, ChangeThreshold> changeThresholds) {
        this.changeThresholds = changeThresholds;
    }

    public Duration getDefaultCollectionInterval() {
        return defaultCollectionInterval;
    }

    public void setDefaultCollectionInterval(Duration defaultCollectionInterval) {
        this.defaultCollectionInterval = defaultCollectionInterval;
    }

    public boolean isImmediateUpdateOnSignificantChange() {
        return immediateUpdateOnSignificantChange;
    }

    public void setImmediateUpdateOnSignificantChange(boolean immediateUpdateOnSignificantChange) {
        this.immediateUpdateOnSignificantChange = immediateUpdateOnSignificantChange;
    }

    public Duration getCollectionTimeout() {
        return collectionTimeout;
    }

    public void setCollectionTimeout(Duration collectionTimeout) {
        this.collectionTimeout = collectionTimeout;
    }
}
