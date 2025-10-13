package io.github.cuihairu.redis.streaming.metrics;

import java.util.Map;

/**
 * Core interface for metrics collection and reporting
 */
public interface MetricsCollector {

    /**
     * Get the name of this metrics collector
     *
     * @return collector name
     */
    String getName();

    /**
     * Record a counter metric
     *
     * @param name the metric name
     * @param value the value to add
     * @param tags optional tags
     */
    void recordCounter(String name, double value, Map<String, String> tags);

    /**
     * Record a gauge metric
     *
     * @param name the metric name
     * @param value the current value
     * @param tags optional tags
     */
    void recordGauge(String name, double value, Map<String, String> tags);

    /**
     * Record a timer metric
     *
     * @param name the metric name
     * @param duration the duration in milliseconds
     * @param tags optional tags
     */
    void recordTimer(String name, long duration, Map<String, String> tags);

    /**
     * Record a histogram metric
     *
     * @param name the metric name
     * @param value the value to record
     * @param tags optional tags
     */
    void recordHistogram(String name, double value, Map<String, String> tags);

    /**
     * Increment a counter by 1
     *
     * @param name the metric name
     * @param tags optional tags
     */
    default void incrementCounter(String name, Map<String, String> tags) {
        recordCounter(name, 1.0, tags);
    }

    /**
     * Record a counter without tags
     *
     * @param name the metric name
     * @param value the value to add
     */
    default void recordCounter(String name, double value) {
        recordCounter(name, value, Map.of());
    }

    /**
     * Record a gauge without tags
     *
     * @param name the metric name
     * @param value the current value
     */
    default void recordGauge(String name, double value) {
        recordGauge(name, value, Map.of());
    }

    /**
     * Record a timer without tags
     *
     * @param name the metric name
     * @param duration the duration in milliseconds
     */
    default void recordTimer(String name, long duration) {
        recordTimer(name, duration, Map.of());
    }

    /**
     * Record a histogram without tags
     *
     * @param name the metric name
     * @param value the value to record
     */
    default void recordHistogram(String name, double value) {
        recordHistogram(name, value, Map.of());
    }

    /**
     * Increment a counter by 1 without tags
     *
     * @param name the metric name
     */
    default void incrementCounter(String name) {
        incrementCounter(name, Map.of());
    }

    /**
     * Start a timing context for a metric
     *
     * @param name the metric name
     * @param tags optional tags
     * @return timing context
     */
    TimingContext startTimer(String name, Map<String, String> tags);

    /**
     * Start a timing context without tags
     *
     * @param name the metric name
     * @return timing context
     */
    default TimingContext startTimer(String name) {
        return startTimer(name, Map.of());
    }

    /**
     * Get all current metric values
     *
     * @return map of metric names to their current values
     */
    Map<String, Double> getCurrentMetrics();

    /**
     * Clear all metrics
     */
    void clear();

    /**
     * Check if the collector is enabled
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Enable or disable the collector
     *
     * @param enabled enable flag
     */
    void setEnabled(boolean enabled);

    /**
     * Timing context interface for measuring execution time
     */
    interface TimingContext extends AutoCloseable {
        /**
         * Stop timing and record the metric
         */
        void stop();

        @Override
        default void close() {
            stop();
        }
    }
}