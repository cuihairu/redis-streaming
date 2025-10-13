package io.github.cuihairu.redis.streaming.metrics;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a single metric measurement
 */
@Data
@AllArgsConstructor
public class MetricMeasurement {

    public enum Type {
        COUNTER,
        GAUGE,
        TIMER,
        HISTOGRAM
    }

    private final String name;
    private final Type type;
    private final double value;
    private final Map<String, String> tags;
    private final Instant timestamp;
    private final String collectorName;

    public MetricMeasurement(String name, Type type, double value, Map<String, String> tags, String collectorName) {
        this(name, type, value, tags, Instant.now(), collectorName);
    }

    /**
     * Create a counter measurement
     *
     * @param name the metric name
     * @param value the counter value
     * @param tags the tags
     * @param collectorName the collector name
     * @return the measurement
     */
    public static MetricMeasurement counter(String name, double value, Map<String, String> tags, String collectorName) {
        return new MetricMeasurement(name, Type.COUNTER, value, tags, collectorName);
    }

    /**
     * Create a gauge measurement
     *
     * @param name the metric name
     * @param value the gauge value
     * @param tags the tags
     * @param collectorName the collector name
     * @return the measurement
     */
    public static MetricMeasurement gauge(String name, double value, Map<String, String> tags, String collectorName) {
        return new MetricMeasurement(name, Type.GAUGE, value, tags, collectorName);
    }

    /**
     * Create a timer measurement
     *
     * @param name the metric name
     * @param duration the duration in milliseconds
     * @param tags the tags
     * @param collectorName the collector name
     * @return the measurement
     */
    public static MetricMeasurement timer(String name, long duration, Map<String, String> tags, String collectorName) {
        return new MetricMeasurement(name, Type.TIMER, duration, tags, collectorName);
    }

    /**
     * Create a histogram measurement
     *
     * @param name the metric name
     * @param value the histogram value
     * @param tags the tags
     * @param collectorName the collector name
     * @return the measurement
     */
    public static MetricMeasurement histogram(String name, double value, Map<String, String> tags, String collectorName) {
        return new MetricMeasurement(name, Type.HISTOGRAM, value, tags, collectorName);
    }

    /**
     * Get the full metric name including tags
     *
     * @return the full metric name
     */
    public String getFullName() {
        if (tags == null || tags.isEmpty()) {
            return name;
        }

        StringBuilder sb = new StringBuilder(name);
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Check if this measurement has tags
     *
     * @return true if has tags
     */
    public boolean hasTags() {
        return tags != null && !tags.isEmpty();
    }

    /**
     * Get a specific tag value
     *
     * @param tagKey the tag key
     * @return the tag value, or null if not found
     */
    public String getTag(String tagKey) {
        return tags != null ? tags.get(tagKey) : null;
    }

    @Override
    public String toString() {
        return String.format("%s{type=%s, value=%.2f, tags=%s, timestamp=%s, collector=%s}",
                name, type, value, tags, timestamp, collectorName);
    }
}