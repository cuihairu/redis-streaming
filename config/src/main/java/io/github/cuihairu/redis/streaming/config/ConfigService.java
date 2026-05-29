package io.github.cuihairu.redis.streaming.config;

import java.util.List;

/**
 * Configuration service interface
 * Provides configuration retrieval, publishing, deletion, and listening capabilities
 *
 * This interface extends ConfigManager, providing backward compatibility
 */
public interface ConfigService extends ConfigManager {
    
    /**
     * Get configuration
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
     * Publish configuration
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
     * Add configuration listener
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @param listener configuration change listener
     */
    void addListener(String dataId, String group, ConfigChangeListener listener);

    /**
     * Remove configuration listener
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @param listener configuration change listener
     */
    void removeListener(String dataId, String group, ConfigChangeListener listener);

    /**
     * Get configuration history
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
