package io.github.cuihairu.redis.streaming.metrics;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a metric with a name, type, value, and optional tags.
 */
public class Metric implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final MetricType type;
    private final double value;
    private final long timestamp;
    private final Map<String, String> tags;

    private Metric(Builder builder) {
        this.name = builder.name;
        this.type = builder.type;
        this.value = builder.value;
        this.timestamp = builder.timestamp;
        this.tags = new HashMap<>(builder.tags);
    }

    public String getName() {
        return name;
    }

    public MetricType getType() {
        return type;
    }

    public double getValue() {
        return value;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Map<String, String> getTags() {
        return new HashMap<>(tags);
    }

    public String getTag(String key) {
        return tags.get(key);
    }

    public static Builder builder(String name, MetricType type) {
        return new Builder(name, type);
    }

    public static class Builder {
        private final String name;
        private final MetricType type;
        private double value;
        private long timestamp;
        private final Map<String, String> tags;

        private Builder(String name, MetricType type) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Metric name cannot be null or empty");
            }
            if (type == null) {
                throw new IllegalArgumentException("Metric type cannot be null");
            }
            this.name = name;
            this.type = type;
            this.timestamp = System.currentTimeMillis();
            this.tags = new HashMap<>();
        }

        public Builder value(double value) {
            this.value = value;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder tag(String key, String value) {
            if (key != null && value != null) {
                this.tags.put(key, value);
            }
            return this;
        }

        public Builder tags(Map<String, String> tags) {
            if (tags != null) {
                this.tags.putAll(tags);
            }
            return this;
        }

        public Metric build() {
            return new Metric(this);
        }
    }

    @Override
    public String toString() {
        return "Metric{" +
                "name='" + name + '\'' +
                ", type=" + type +
                ", value=" + value +
                ", timestamp=" + timestamp +
                ", tags=" + tags +
                '}';
    }
}
