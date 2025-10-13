package io.github.cuihairu.redis.streaming.metrics;

import java.io.Serializable;
import java.util.Map;

/**
 * Interface for collecting and reporting metrics.
 */
public interface MetricCollector extends Serializable {

    /**
     * Increment a counter by 1
     *
     * @param name The name of the counter
     */
    void incrementCounter(String name);

    /**
     * Increment a counter by a specific amount
     *
     * @param name The name of the counter
     * @param amount The amount to increment by
     */
    void incrementCounter(String name, long amount);

    /**
     * Set a gauge value
     *
     * @param name The name of the gauge
     * @param value The value to set
     */
    void setGauge(String name, double value);

    /**
     * Record a value in a histogram
     *
     * @param name The name of the histogram
     * @param value The value to record
     */
    void recordHistogram(String name, double value);

    /**
     * Mark an event in a meter
     *
     * @param name The name of the meter
     */
    void markMeter(String name);

    /**
     * Record a timer duration
     *
     * @param name The name of the timer
     * @param durationMillis The duration in milliseconds
     */
    void recordTimer(String name, long durationMillis);

    /**
     * Increment a counter with tags
     *
     * @param name The name of the counter
     * @param tags Tags to associate with the metric
     */
    void incrementCounter(String name, Map<String, String> tags);

    /**
     * Set a gauge value with tags
     *
     * @param name The name of the gauge
     * @param value The value to set
     * @param tags Tags to associate with the metric
     */
    void setGauge(String name, double value, Map<String, String> tags);

    /**
     * Get all collected metrics
     *
     * @return Map of metric names to metrics
     */
    Map<String, Metric> getMetrics();

    /**
     * Get a specific metric by name
     *
     * @param name The name of the metric
     * @return The metric, or null if not found
     */
    Metric getMetric(String name);

    /**
     * Clear all metrics
     */
    void clear();
}
