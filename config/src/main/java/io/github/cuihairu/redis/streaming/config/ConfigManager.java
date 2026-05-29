package io.github.cuihairu.redis.streaming.config;

import java.util.List;

/**
 * Configuration manager interface
 * Inspired by Nacos Config Center design, providing unified configuration management capabilities
 *
 * Core concepts:
 * - Config Publisher: publishes and manages configurations
 * - Config Subscriber: retrieves and listens for configuration changes
 */
public interface ConfigManager {
    
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
     * Start the configuration manager
     */
    void start();
    
    /**
     * Stop the configuration manager
     */
    void stop();
    
    /**
     * Check if the configuration manager is running
     *
     * @return true if running, false otherwise
     */
    boolean isRunning();
}
