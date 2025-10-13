package io.github.cuihairu.redis.streaming.metrics;

import java.util.Map;

/**
 * Event listener interface for metrics-related events
 */
public interface MetricsEventListener {

    /**
     * Called when a metric is recorded
     *
     * @param collectorName the collector name
     * @param metricName the metric name
     * @param value the metric value
     * @param tags the metric tags
     */
    default void onMetricRecorded(String collectorName, String metricName, double value, Map<String, String> tags) {}

    /**
     * Called when a collector is registered
     *
     * @param collectorName the collector name
     */
    default void onCollectorRegistered(String collectorName) {}

    /**
     * Called when a collector is unregistered
     *
     * @param collectorName the collector name
     */
    default void onCollectorUnregistered(String collectorName) {}

    /**
     * Called when an error occurs in metrics collection
     *
     * @param collectorName the collector name
     * @param error the error
     */
    default void onMetricsError(String collectorName, Throwable error) {}

    /**
     * Called when metrics are exported/reported
     *
     * @param collectorName the collector name
     * @param exportedCount the number of metrics exported
     */
    default void onMetricsExported(String collectorName, int exportedCount) {}

    /**
     * Called when a collector's enabled state changes
     *
     * @param collectorName the collector name
     * @param enabled the new enabled state
     */
    default void onCollectorEnabledChanged(String collectorName, boolean enabled) {}
}