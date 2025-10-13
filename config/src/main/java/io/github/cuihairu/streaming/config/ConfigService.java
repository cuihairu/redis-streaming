package io.github.cuihairu.streaming.config;

import java.util.List;

/**
 * 配置服务接口
 * 提供配置的获取、发布、删除和监听功能
 * 
 * 此接口继承ConfigManager，提供向后兼容性
 */
public interface ConfigService extends ConfigManager {
    
    /**
     * 获取配置
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @return 配置内容，如果不存在返回null
     */
    String getConfig(String dataId, String group);
    
    /**
     * 获取配置，带默认值
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param defaultValue 默认值
     * @return 配置内容，如果不存在返回默认值
     */
    default String getConfig(String dataId, String group, String defaultValue) {
        String config = getConfig(dataId, group);
        return config != null ? config : defaultValue;
    }
    
    /**
     * 发布配置
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param content 配置内容
     * @return 发布成功返回true，否则返回false
     */
    boolean publishConfig(String dataId, String group, String content);
    
    /**
     * 发布配置，带描述
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param content 配置内容
     * @param description 配置描述
     * @return 发布成功返回true，否则返回false
     */
    boolean publishConfig(String dataId, String group, String content, String description);
    
    /**
     * 删除配置
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @return 删除成功返回true，否则返回false
     */
    boolean removeConfig(String dataId, String group);
    
    /**
     * 添加配置监听器
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param listener 配置变更监听器
     */
    void addListener(String dataId, String group, ConfigChangeListener listener);
    
    /**
     * 移除配置监听器
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param listener 配置变更监听器
     */
    void removeListener(String dataId, String group, ConfigChangeListener listener);
    
    /**
     * 获取配置历史
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param size 获取的历史记录数量
     * @return 配置历史列表
     */
    List<ConfigHistory> getConfigHistory(String dataId, String group, int size);
    
    /**
     * 启动配置服务
     */
    void start();
    
    /**
     * 停止配置服务
     */
    void stop();
    
    /**
     * 检查配置服务是否正在运行
     *
     * @return 如果正在运行返回true，否则返回false
     */
    default boolean isRunning() {
        return false;
    }
}