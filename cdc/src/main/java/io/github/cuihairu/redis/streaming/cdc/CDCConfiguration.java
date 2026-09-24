package io.github.cuihairu.redis.streaming.cdc;

import java.util.List;
import java.util.Map;

/**
 * CDC connector configuration
 */
public interface CDCConfiguration {

    /**
     * Get connector name
     *
     * @return connector name
     */
    String getName();

    /**
     * Get connector type
     *
     * @return connector type (e.g., "mysql-binlog", "postgres-logical", "polling")
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
     * Get database connection URL
     *
     * @return database connection URL
     */
    String getDatabaseUrl();

    /**
     * Get database username
     *
     * @return database username
     */
    String getUsername();

    /**
     * Get database password
     *
     * @return database password
     */
    String getPassword();

    /**
     * Get list of tables to monitor (empty means all tables)
     *
     * @return list of table names or patterns
     */
    List<String> getTableIncludes();

    /**
     * Get list of tables to exclude from monitoring
     *
     * @return list of table names or patterns to exclude
     */
    List<String> getTableExcludes();

    /**
     * Get polling interval in milliseconds (for polling CDC)
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
     * Check if an initial snapshot of existing rows should be taken on startup.
     *
     * <p>When {@code true} (property {@code snapshot.enabled}) and the snapshot mode is not
     * {@code never}, polling connectors emit rows that already exist as INSERT events at
     * startup instead of starting from {@code MAX(incremental column)} and silently skipping
     * them. Defaults to {@code false} (baseline at MAX, existing rows skipped).
     *
     * @return true if snapshot enabled
     */
    boolean isSnapshotEnabled();

    /**
     * Get snapshot mode (property {@code snapshot.mode}).
     *
     * <ul>
     *   <li>{@code initial} (default): with snapshot enabled, capture existing rows at startup</li>
     *   <li>{@code when_needed}: for polling connectors equivalent to {@code initial}</li>
     *   <li>{@code never}: never capture existing rows; always baseline at MAX even if
     *       {@code snapshot.enabled=true}</li>
     * </ul>
     *
     * @return snapshot mode (initial, never, when_needed, etc.)
     */
    String getSnapshotMode();

    /**
     * Validate the configuration
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    void validate() throws IllegalArgumentException;
}