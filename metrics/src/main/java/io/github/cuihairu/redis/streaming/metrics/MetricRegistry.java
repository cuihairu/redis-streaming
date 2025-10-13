package io.github.cuihairu.redis.streaming.metrics;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing metric collectors.
 * Allows multiple collectors to be registered and metrics to be reported to all of them.
 */
public class MetricRegistry implements Serializable {

    private static final long serialVersionUID = 1L;

    private final Map<String, MetricCollector> collectors;
    private final MetricCollector defaultCollector;

    public MetricRegistry() {
        this.collectors = new ConcurrentHashMap<>();
        this.defaultCollector = new InMemoryMetricCollector();
        registerCollector("default", defaultCollector);
    }

    /**
     * Register a metric collector
     *
     * @param name The name of the collector
     * @param collector The collector to register
     */
    public void registerCollector(String name, MetricCollector collector) {
        collectors.put(name, collector);
    }

    /**
     * Get a collector by name
     *
     * @param name The name of the collector
     * @return The collector, or null if not found
     */
    public MetricCollector getCollector(String name) {
        return collectors.get(name);
    }

    /**
     * Get the default collector
     *
     * @return The default collector
     */
    public MetricCollector getDefaultCollector() {
        return defaultCollector;
    }

    /**
     * Increment a counter in all collectors
     *
     * @param name The name of the counter
     */
    public void incrementCounter(String name) {
        collectors.values().forEach(c -> c.incrementCounter(name));
    }

    /**
     * Set a gauge in all collectors
     *
     * @param name The name of the gauge
     * @param value The value to set
     */
    public void setGauge(String name, double value) {
        collectors.values().forEach(c -> c.setGauge(name, value));
    }

    /**
     * Record a histogram value in all collectors
     *
     * @param name The name of the histogram
     * @param value The value to record
     */
    public void recordHistogram(String name, double value) {
        collectors.values().forEach(c -> c.recordHistogram(name, value));
    }

    /**
     * Mark a meter in all collectors
     *
     * @param name The name of the meter
     */
    public void markMeter(String name) {
        collectors.values().forEach(c -> c.markMeter(name));
    }

    /**
     * Record a timer duration in all collectors
     *
     * @param name The name of the timer
     * @param durationMillis The duration in milliseconds
     */
    public void recordTimer(String name, long durationMillis) {
        collectors.values().forEach(c -> c.recordTimer(name, durationMillis));
    }

    /**
     * Get all metrics from all collectors
     *
     * @return List of all metrics
     */
    public List<Metric> getAllMetrics() {
        List<Metric> allMetrics = new ArrayList<>();
        collectors.values().forEach(c ->
                allMetrics.addAll(c.getMetrics().values())
        );
        return allMetrics;
    }

    /**
     * Clear all metrics from all collectors
     */
    public void clearAll() {
        collectors.values().forEach(MetricCollector::clear);
    }

    /**
     * Get the number of registered collectors
     *
     * @return The number of collectors
     */
    public int getCollectorCount() {
        return collectors.size();
    }
}
