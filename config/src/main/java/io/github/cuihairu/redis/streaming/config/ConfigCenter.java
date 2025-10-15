package io.github.cuihairu.redis.streaming.config;

/**
 * 配置中心接口
 * 参考Nacos Config Center设计，提供统一的配置管理能力
 * 
 * 这个接口整合了配置管理功能，为应用提供统一的配置访问入口
 */
public interface ConfigCenter extends ConfigManager {
    
    /**
     * 检查配置是否存在
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @return 如果配置存在返回true，否则返回false
     */
    boolean hasConfig(String dataId, String group);
    
    /**
     * 获取配置的元数据信息
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @return 配置的元数据信息
     */
    ConfigMetadata getConfigMetadata(String dataId, String group);
    
    /**
     * 配置元数据信息
     */
    interface ConfigMetadata {
        /**
         * 获取配置版本
         */
        String getVersion();
        
        /**
         * 获取配置描述
         */
        String getDescription();
        
        /**
         * 获取创建时间
         */
        long getCreateTime();
        
        /**
         * 获取最后更新时间
         */
        long getLastModified();
        
        /**
         * 获取配置大小（字节）
         */
        long getSize();
    }
}
