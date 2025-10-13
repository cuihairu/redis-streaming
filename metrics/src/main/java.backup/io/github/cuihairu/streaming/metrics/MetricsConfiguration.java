package io.github.cuihairu.redis.streaming.metrics;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for metrics collection
 */
@Data
@AllArgsConstructor
public class MetricsConfiguration {

    private final String applicationName;
    private final boolean enabled;
    private final Duration reportingInterval;
    private final Map<String, Object> properties;

    public MetricsConfiguration(String applicationName) {
        this(applicationName, true, Duration.ofMinutes(1), new HashMap<>());
    }

    public MetricsConfiguration(String applicationName, boolean enabled) {
        this(applicationName, enabled, Duration.ofMinutes(1), new HashMap<>());
    }

    /**
     * Get a property value
     *
     * @param key the property key
     * @return the property value, or null if not found
     */
    public Object getProperty(String key) {
        return properties.get(key);
    }

    /**
     * Get a property value with default
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the property value, or default if not found
     */
    public Object getProperty(String key, Object defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    /**
     * Get a string property
     *
     * @param key the property key
     * @return the string value, or null if not found
     */
    public String getStringProperty(String key) {
        Object value = getProperty(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Get a string property with default
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the string value, or default if not found
     */
    public String getStringProperty(String key, String defaultValue) {
        Object value = getProperty(key, defaultValue);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Get an integer property
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the integer value, or default if not found or not parseable
     */
    public int getIntProperty(String key, int defaultValue) {
        Object value = getProperty(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Get a long property
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the long value, or default if not found or not parseable
     */
    public long getLongProperty(String key, long defaultValue) {
        Object value = getProperty(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Get a boolean property
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the boolean value, or default if not found or not parseable
     */
    public boolean getBooleanProperty(String key, boolean defaultValue) {
        Object value = getProperty(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    /**
     * Set a property
     *
     * @param key the property key
     * @param value the property value
     */
    public void setProperty(String key, Object value) {
        properties.put(key, value);
    }

    /**
     * Validate the configuration
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (applicationName == null || applicationName.trim().isEmpty()) {
            throw new IllegalArgumentException("Application name cannot be null or empty");
        }

        if (reportingInterval == null || reportingInterval.isNegative() || reportingInterval.isZero()) {
            throw new IllegalArgumentException("Reporting interval must be positive");
        }
    }

    /**
     * Create a copy of this configuration with a different application name
     *
     * @param newApplicationName the new application name
     * @return new configuration instance
     */
    public MetricsConfiguration withApplicationName(String newApplicationName) {
        return new MetricsConfiguration(newApplicationName, enabled, reportingInterval, new HashMap<>(properties));
    }

    /**
     * Create a copy of this configuration with enabled flag
     *
     * @param newEnabled the new enabled flag
     * @return new configuration instance
     */
    public MetricsConfiguration withEnabled(boolean newEnabled) {
        return new MetricsConfiguration(applicationName, newEnabled, reportingInterval, new HashMap<>(properties));
    }

    /**
     * Create a copy of this configuration with a different reporting interval
     *
     * @param newReportingInterval the new reporting interval
     * @return new configuration instance
     */
    public MetricsConfiguration withReportingInterval(Duration newReportingInterval) {
        return new MetricsConfiguration(applicationName, enabled, newReportingInterval, new HashMap<>(properties));
    }

    @Override
    public String toString() {
        return String.format("MetricsConfiguration{applicationName='%s', enabled=%s, reportingInterval=%s, properties=%d}",
                applicationName, enabled, reportingInterval, properties.size());
    }
}