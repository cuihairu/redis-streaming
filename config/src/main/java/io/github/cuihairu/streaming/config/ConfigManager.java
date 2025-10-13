package io.github.cuihairu.streaming.config;

import java.util.List;

/**
 * 配置管理器接口
 * 参考Nacos Config Center设计，提供统一的配置管理能力
 * 
 * 核心概念：
 * - Config Publisher（配置发布者）：发布和管理配置
 * - Config Subscriber（配置订阅者）：获取和监听配置变更
 */
public interface ConfigManager {
    
    // ==================== 配置发布者接口 ====================
    
    /**
     * 发布配置
     * 配置发布者调用此方法向配置中心发布配置
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param content 配置内容
     * @return 发布成功返回true，否则返回false
     */
    boolean publishConfig(String dataId, String group, String content);
    
    /**
     * 发布配置，带描述信息
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
     * 获取配置历史记录
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param size 历史记录数量
     * @return 配置历史记录列表
     */
    List<ConfigHistory> getConfigHistory(String dataId, String group, int size);
    
    // ==================== 配置订阅者接口 ====================
    
    /**
     * 获取配置
     * 配置订阅者调用此方法获取配置内容
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
     * 添加配置变更监听器
     * 配置订阅者调用此方法监听配置变更
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param listener 配置变更监听器
     */
    void addListener(String dataId, String group, ConfigChangeListener listener);
    
    /**
     * 移除配置变更监听器
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param listener 配置变更监听器
     */
    void removeListener(String dataId, String group, ConfigChangeListener listener);
    
    // ==================== 生命周期管理 ====================
    
    /**
     * 启动配置管理器
     */
    void start();
    
    /**
     * 停止配置管理器
     */
    void stop();
    
    /**
     * 检查配置管理器是否正在运行
     * 
     * @return 如果正在运行返回true，否则返回false
     */
    boolean isRunning();
}