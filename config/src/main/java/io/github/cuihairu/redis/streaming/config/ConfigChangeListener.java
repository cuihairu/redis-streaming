package io.github.cuihairu.redis.streaming.config;

/**
 * Configuration change listener
 * Listens for configuration change events
 */
public interface ConfigChangeListener {
    
    /**
     * Configuration change event
     *
     * @param dataId configuration ID
     * @param group configuration group
     * @param content new configuration content
     * @param version configuration version
     */
    void onConfigChange(String dataId, String group, String content, String version);
}
