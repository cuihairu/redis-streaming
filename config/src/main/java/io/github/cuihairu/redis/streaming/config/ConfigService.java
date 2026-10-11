package io.github.cuihairu.redis.streaming.config;

import java.util.List;

/**
 * Configuration service interface: the single contract of the config module, covering
 * both the publisher view (publish/remove/history) and the subscriber view (get/listen).
 * Inspired by Nacos Config Center design.
 *
 * <p>Former sibling interfaces ({@code ConfigManager}, {@code ConfigCenter}) were merged
 * here; {@code hasConfig} and the history trim defaults were their only survivors.
 */
public interface ConfigService {

    // ==================== Configuration Publisher Interface ====================

    /**
     * Publish configuration
     * Configuration publishers call this method to publish configurations to the config center
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @param content configuration content
     * @return true if published successfully, false otherwise
     */
    boolean publishConfig(String dataId, String group, String content);

    /**
     * Publish configuration with description
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @param content configuration content
     * @param description configuration description
     * @return true if published successfully, false otherwise
     */
    boolean publishConfig(String dataId, String group, String content, String description);

    /**
     * Remove configuration
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @return true if removed successfully, false otherwise
     */
    boolean removeConfig(String dataId, String group);

    /**
     * Get configuration history records
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @param size number of history records to retrieve
     * @return list of configuration history records
     */
    List<ConfigHistory> getConfigHistory(String dataId, String group, int size);

    /**
     * Trim history to keep at most maxSize newest records.
     * @return removed count
     */
    default int trimHistoryBySize(String dataId, String group, int maxSize) { return 0; }

    /**
     * Trim history records older than maxAge.
     * @return removed count
     */
    default int trimHistoryByAge(String dataId, String group, java.time.Duration maxAge) { return 0; }

    // ==================== Configuration Subscriber Interface ====================

    /**
     * Get configuration
     * Configuration subscribers call this method to retrieve configuration content
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @return configuration content, or null if not found
     */
    String getConfig(String dataId, String group);

    /**
     * Get configuration with default value
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @param defaultValue default value
     * @return configuration content, or default value if not found
     */
    default String getConfig(String dataId, String group, String defaultValue) {
        String config = getConfig(dataId, group);
        return config != null ? config : defaultValue;
    }

    /**
     * Check if a configuration exists
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @return true if the configuration exists, false otherwise
     */
    default boolean hasConfig(String dataId, String group) {
        return getConfig(dataId, group) != null;
    }

    /**
     * Add configuration change listener
     * Configuration subscribers call this method to listen for configuration changes
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @param listener configuration change listener
     */
    void addListener(String dataId, String group, ConfigChangeListener listener);

    /**
     * Remove configuration change listener
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @param listener configuration change listener
     */
    void removeListener(String dataId, String group, ConfigChangeListener listener);

    // ==================== Lifecycle Management ====================

    /**
     * Start the configuration service
     */
    void start();

    /**
     * Stop the configuration service
     */
    void stop();

    /**
     * Check if the configuration service is running
     *
     * @return true if running, false otherwise
     */
    default boolean isRunning() {
        return false;
    }
}
