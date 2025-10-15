package io.github.cuihairu.redis.streaming.config;

/**
 * 配置服务Redis配置
 * 包含配置服务专用的Redis键模式和配置
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
     * 获取配置键
     * 
     * @param group 配置组
     * @param dataId 配置ID
     * @return 配置键
     */
    public String getConfigKey(String group, String dataId) {
        return formatKey("config:%s:%s", sanitize(group), sanitize(dataId));
    }
    
    /**
     * 获取配置订阅者列表键
     * 
     * @param group 配置组
     * @param dataId 配置ID
     * @return 配置订阅者列表键
     */
    public String getConfigSubscribersKey(String group, String dataId) {
        return formatKey("config_subscribers:%s:%s", sanitize(group), sanitize(dataId));
    }
    
    /**
     * 获取配置历史键
     * 
     * @param group 配置组
     * @param dataId 配置ID
     * @return 配置历史键
     */
    public String getConfigHistoryKey(String group, String dataId) {
        return formatKey("config_history:%s:%s", sanitize(group), sanitize(dataId));
    }
    
    /**
     * 获取配置变更通道键
     * 
     * @param group 配置组
     * @param dataId 配置ID
     * @return 配置变更通道键
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
