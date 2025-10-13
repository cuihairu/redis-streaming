package io.github.cuihairu.redis.streaming.metrics;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Builder for creating metrics configurations
 */
public class MetricsConfigurationBuilder {

    private String applicationName;
    private boolean enabled = true;
    private Duration reportingInterval = Duration.ofMinutes(1);
    private final Map<String, Object> properties = new HashMap<>();

    /**
     * Set the application name
     *
     * @param applicationName the application name
     * @return this builder
     */
    public MetricsConfigurationBuilder applicationName(String applicationName) {
        this.applicationName = applicationName;
        return this;
    }

    /**
     * Set whether metrics collection is enabled
     *
     * @param enabled true to enable metrics collection
     * @return this builder
     */
    public MetricsConfigurationBuilder enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Set the reporting interval
     *
     * @param reportingInterval the reporting interval
     * @return this builder
     */
    public MetricsConfigurationBuilder reportingInterval(Duration reportingInterval) {
        this.reportingInterval = reportingInterval;
        return this;
    }

    /**
     * Set a custom property
     *
     * @param key the property key
     * @param value the property value
     * @return this builder
     */
    public MetricsConfigurationBuilder property(String key, Object value) {
        this.properties.put(key, value);
        return this;
    }

    /**
     * Set multiple properties
     *
     * @param properties the properties to add
     * @return this builder
     */
    public MetricsConfigurationBuilder properties(Map<String, Object> properties) {
        this.properties.putAll(properties);
        return this;
    }

    // Prometheus-specific configuration methods

    /**
     * Set Prometheus step interval
     *
     * @param step the step interval
     * @return this builder
     */
    public MetricsConfigurationBuilder prometheusStep(Duration step) {
        return property("prometheus.step", step.toString());
    }

    /**
     * Set common tags for all metrics
     *
     * @param tags comma-separated tags in format "key1=value1,key2=value2"
     * @return this builder
     */
    public MetricsConfigurationBuilder commonTags(String tags) {
        return property("common.tags", tags);
    }

    /**
     * Add a common tag
     *
     * @param key the tag key
     * @param value the tag value
     * @return this builder
     */
    public MetricsConfigurationBuilder commonTag(String key, String value) {
        String existingTags = (String) properties.get("common.tags");
        String newTag = key + "=" + value;

        if (existingTags == null || existingTags.isEmpty()) {
            return property("common.tags", newTag);
        } else {
            return property("common.tags", existingTags + "," + newTag);
        }
    }

    // Registry-specific configuration methods

    /**
     * Set registry type
     *
     * @param registryType the registry type (e.g., "prometheus", "simple", "micrometer")
     * @return this builder
     */
    public MetricsConfigurationBuilder registryType(String registryType) {
        return property("registry.type", registryType);
    }

    /**
     * Enable JVM metrics collection
     *
     * @param enabled true to enable JVM metrics
     * @return this builder
     */
    public MetricsConfigurationBuilder jvmMetrics(boolean enabled) {
        return property("jvm.metrics.enabled", enabled);
    }

    /**
     * Enable system metrics collection
     *
     * @param enabled true to enable system metrics
     * @return this builder
     */
    public MetricsConfigurationBuilder systemMetrics(boolean enabled) {
        return property("system.metrics.enabled", enabled);
    }

    /**
     * Set metrics export endpoint
     *
     * @param endpoint the export endpoint URL
     * @return this builder
     */
    public MetricsConfigurationBuilder exportEndpoint(String endpoint) {
        return property("export.endpoint", endpoint);
    }

    /**
     * Set metrics export format
     *
     * @param format the export format (e.g., "prometheus", "json")
     * @return this builder
     */
    public MetricsConfigurationBuilder exportFormat(String format) {
        return property("export.format", format);
    }

    /**
     * Build the configuration
     *
     * @return the metrics configuration
     * @throws IllegalArgumentException if required fields are missing
     */
    public MetricsConfiguration build() {
        if (applicationName == null || applicationName.trim().isEmpty()) {
            throw new IllegalArgumentException("Application name is required");
        }

        if (reportingInterval == null) {
            throw new IllegalArgumentException("Reporting interval cannot be null");
        }

        MetricsConfiguration config = new MetricsConfiguration(
                applicationName,
                enabled,
                reportingInterval,
                new HashMap<>(properties)
        );

        config.validate();
        return config;
    }

    // Static factory methods

    /**
     * Create a builder for a Prometheus-based configuration
     *
     * @param applicationName the application name
     * @return a new builder with Prometheus defaults
     */
    public static MetricsConfigurationBuilder forPrometheus(String applicationName) {
        return new MetricsConfigurationBuilder()
                .applicationName(applicationName)
                .registryType("prometheus")
                .jvmMetrics(true)
                .systemMetrics(true);
    }

    /**
     * Create a builder for a simple in-memory configuration
     *
     * @param applicationName the application name
     * @return a new builder with simple defaults
     */
    public static MetricsConfigurationBuilder forSimple(String applicationName) {
        return new MetricsConfigurationBuilder()
                .applicationName(applicationName)
                .registryType("simple");
    }

    /**
     * Create a builder for a custom Micrometer configuration
     *
     * @param applicationName the application name
     * @return a new builder with Micrometer defaults
     */
    public static MetricsConfigurationBuilder forMicrometer(String applicationName) {
        return new MetricsConfigurationBuilder()
                .applicationName(applicationName)
                .registryType("micrometer")
                .jvmMetrics(true);
    }

    /**
     * Create a minimal configuration
     *
     * @param applicationName the application name
     * @return a new builder with minimal defaults
     */
    public static MetricsConfigurationBuilder minimal(String applicationName) {
        return new MetricsConfigurationBuilder()
                .applicationName(applicationName)
                .enabled(true)
                .reportingInterval(Duration.ofMinutes(5));
    }
}