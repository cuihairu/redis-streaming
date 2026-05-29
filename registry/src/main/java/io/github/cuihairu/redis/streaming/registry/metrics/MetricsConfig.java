package io.github.cuihairu.redis.streaming.registry.metrics;

import java.time.Duration;
import java.util.*;
import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThresholdType;

/**
 * Metric collectionConfiguration
 */
public class MetricsConfig {

    // Enabled metric types
    private Set<String> enabledMetrics = Set.of("memory", "cpu", "application");

    // Collection intervals per metric type (overrides default value)
    private Map<String, Duration> collectionIntervals = Map.of(
            "memory", Duration.ofSeconds(30),
            "cpu", Duration.ofSeconds(60),
            "disk", Duration.ofMinutes(5),
            "network", Duration.ofMinutes(10)
    );

    // MetricChange threshold
    private Map<String, ChangeThreshold> changeThresholds = Map.of(
            MetricKeys.MEMORY_HEAP_USAGE_PERCENT, new ChangeThreshold(10.0, ChangeThresholdType.ABSOLUTE),
            MetricKeys.CPU_PROCESS_LOAD, new ChangeThreshold(0.20, ChangeThresholdType.ABSOLUTE),
            MetricKeys.DISK_USAGE_PERCENT, new ChangeThreshold(5.0, ChangeThresholdType.ABSOLUTE)
    );

    // DefaultCollectInterval
    private Duration defaultCollectionInterval = Duration.ofMinutes(1);

    // Whether to update immediately when significant changes are detected
    private boolean immediateUpdateOnSignificantChange = true;

    // Collection timeout
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
