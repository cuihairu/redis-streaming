package io.github.cuihairu.streaming.config;

/**
 * 配置服务Redis配置
 * 包含配置服务专用的Redis键模式和配置
 */
public class ConfigServiceConfig extends BaseRedisConfig {
    
    public ConfigServiceConfig() {
        super();
    }
    
    public ConfigServiceConfig(String keyPrefix) {
        super(keyPrefix);
    }
    
    public ConfigServiceConfig(String keyPrefix, boolean enableKeyPrefix) {
        super(keyPrefix, enableKeyPrefix);
    }
    
    /**
     * 获取配置键
     * 
     * @param group 配置组
     * @param dataId 配置ID
     * @return 配置键
     */
    public String getConfigKey(String group, String dataId) {
        return formatKey("config:%s:%s", group, dataId);
    }
    
    /**
     * 获取配置订阅者列表键
     * 
     * @param group 配置组
     * @param dataId 配置ID
     * @return 配置订阅者列表键
     */
    public String getConfigSubscribersKey(String group, String dataId) {
        return formatKey("config_subscribers:%s:%s", group, dataId);
    }
    
    /**
     * 获取配置历史键
     * 
     * @param group 配置组
     * @param dataId 配置ID
     * @return 配置历史键
     */
    public String getConfigHistoryKey(String group, String dataId) {
        return formatKey("config_history:%s:%s", group, dataId);
    }
    
    /**
     * 获取配置变更通道键
     * 
     * @param group 配置组
     * @param dataId 配置ID
     * @return 配置变更通道键
     */
    public String getConfigChangeChannelKey(String group, String dataId) {
        return formatKey("config_change:%s:%s", group, dataId);
    }
}