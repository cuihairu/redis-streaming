package io.github.cuihairu.redis.streaming.config;

/**
 * Configuration service Redis configuration
 * Contains Redis key patterns and settings specific to the configuration service
 */
public class ConfigServiceConfig extends BaseRedisConfig {
    /**
     * Max history size to retain per config (default 10)
     */
    private int historySize = 10;
    
    public ConfigServiceConfig() {
        super();
    }
    
    public ConfigServiceConfig(String keyPrefix) {
        super(keyPrefix);
    }
    
    public ConfigServiceConfig(String keyPrefix, boolean enableKeyPrefix) {
        super(keyPrefix, enableKeyPrefix);
    }

    public int getHistorySize() {
        return historySize;
    }

    public void setHistorySize(int historySize) {
        this.historySize = Math.max(0, historySize);
    }
    
    /**
     * Get configuration key
     *
     * @param group configuration group
     * @param dataId configuration ID
     * @return configuration key
     */
    public String getConfigKey(String group, String dataId) {
        return formatKey("config:%s:%s", sanitize(group), sanitize(dataId));
    }
    
    /**
     * Get configuration subscribers list key
     *
     * @param group configuration group
     * @param dataId configuration ID
     * @return configuration subscribers list key
     */
    public String getConfigSubscribersKey(String group, String dataId) {
        return formatKey("config_subscribers:%s:%s", sanitize(group), sanitize(dataId));
    }
    
    /**
     * Get configuration history key
     *
     * @param group configuration group
     * @param dataId configuration ID
     * @return configuration history key
     */
    public String getConfigHistoryKey(String group, String dataId) {
        return formatKey("config_history:%s:%s", sanitize(group), sanitize(dataId));
    }
    
    /**
     * Get configuration change channel key
     *
     * @param group configuration group
     * @param dataId configuration ID
     * @return configuration change channel key
     */
    public String getConfigChangeChannelKey(String group, String dataId) {
        return formatKey("config_change:%s:%s", sanitize(group), sanitize(dataId));
    }

    private String sanitize(String s) {
        if (s == null) return "";
        String t = s.trim();
        // Replace Redis-unfriendly separators to avoid key parsing ambiguity
        t = t.replace(':', '_');
        return t;
    }
}
