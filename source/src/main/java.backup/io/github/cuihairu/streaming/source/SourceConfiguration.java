package io.github.cuihairu.redis.streaming.source;

import java.util.Map;

/**
 * Source connector configuration
 */
public interface SourceConfiguration {

    /**
     * Get connector name
     *
     * @return connector name
     */
    String getName();

    /**
     * Get connector type
     *
     * @return connector type (e.g., "http", "file", "iot")
     */
    String getType();

    /**
     * Get all configuration properties
     *
     * @return configuration properties
     */
    Map<String, Object> getProperties();

    /**
     * Get a configuration property
     *
     * @param key property key
     * @return property value, null if not found
     */
    Object getProperty(String key);

    /**
     * Get a configuration property with default value
     *
     * @param key property key
     * @param defaultValue default value if property not found
     * @return property value or default value
     */
    Object getProperty(String key, Object defaultValue);

    /**
     * Get polling interval in milliseconds
     *
     * @return polling interval
     */
    long getPollingIntervalMs();

    /**
     * Get batch size for polling
     *
     * @return batch size
     */
    int getBatchSize();

    /**
     * Check if connector should auto-start
     *
     * @return true if auto-start enabled
     */
    boolean isAutoStart();

    /**
     * Validate the configuration
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    void validate() throws IllegalArgumentException;
}