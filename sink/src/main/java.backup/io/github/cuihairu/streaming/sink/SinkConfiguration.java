package io.github.cuihairu.redis.streaming.sink;

import java.util.Map;

/**
 * Sink connector configuration
 */
public interface SinkConfiguration {

    /**
     * Get connector name
     *
     * @return connector name
     */
    String getName();

    /**
     * Get connector type
     *
     * @return connector type (e.g., "elasticsearch", "database", "file")
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
     * Get batch size for writing
     *
     * @return batch size
     */
    int getBatchSize();

    /**
     * Get flush interval in milliseconds
     *
     * @return flush interval
     */
    long getFlushIntervalMs();

    /**
     * Get maximum retry attempts
     *
     * @return max retry attempts
     */
    int getMaxRetries();

    /**
     * Get retry backoff interval in milliseconds
     *
     * @return retry backoff interval
     */
    long getRetryBackoffMs();

    /**
     * Check if connector should auto-start
     *
     * @return true if auto-start enabled
     */
    boolean isAutoStart();

    /**
     * Check if connector should auto-flush
     *
     * @return true if auto-flush enabled
     */
    boolean isAutoFlush();

    /**
     * Validate the configuration
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    void validate() throws IllegalArgumentException;
}