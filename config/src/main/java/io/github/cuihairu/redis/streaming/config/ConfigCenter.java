package io.github.cuihairu.redis.streaming.config;

/**
 * Configuration center interface
 * Inspired by Nacos Config Center design, providing unified configuration management capabilities
 *
 * This interface integrates configuration management functionality, providing a unified configuration access entry point for applications
 */
public interface ConfigCenter extends ConfigManager {
    
    /**
     * Check if configuration exists
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @return true if the configuration exists, false otherwise
     */
    boolean hasConfig(String dataId, String group);
    
    /**
     * Get configuration metadata
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @return configuration metadata
     */
    ConfigMetadata getConfigMetadata(String dataId, String group);
    
    /**
     * Configuration metadata
     */
    interface ConfigMetadata {
        /**
         * Get configuration version
         */
        String getVersion();

        /**
         * Get configuration description
         */
        String getDescription();

        /**
         * Get creation time
         */
        long getCreateTime();

        /**
         * Get last modified time
         */
        long getLastModified();

        /**
         * Get configuration size in bytes
         */
        long getSize();
    }
}
