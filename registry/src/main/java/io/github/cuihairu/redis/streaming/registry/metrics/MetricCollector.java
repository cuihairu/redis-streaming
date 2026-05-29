package io.github.cuihairu.redis.streaming.registry.metrics;

/**
 * Metric collector interface
 */
public interface MetricCollector {

    /**
     * Metric type identifier
     */
    String getMetricType();

    /**
     * Collect metric data
     */
    Object collectMetric() throws Exception;

    /**
     * Whether the metric collector is available (checks runtime environment dependencies)
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Overhead level of metric collection
     */
    default CollectionCost getCost() {
        return CollectionCost.LOW;
    }
}