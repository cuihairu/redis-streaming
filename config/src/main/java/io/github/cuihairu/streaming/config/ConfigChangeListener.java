package io.github.cuihairu.streaming.config;

/**
 * 配置变更监听器
 * 监听配置的变更事件
 */
public interface ConfigChangeListener {
    
    /**
     * 配置变更事件
     * 
     * @param dataId 配置ID
     * @param group 配置组
     * @param content 新的配置内容
     * @param version 配置版本
     */
    void onConfigChange(String dataId, String group, String content, String version);
}