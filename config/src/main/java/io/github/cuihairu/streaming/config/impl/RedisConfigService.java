package io.github.cuihairu.streaming.config.impl;

import io.github.cuihairu.streaming.config.*;
import org.redisson.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于Redis的配置服务实现
 */
public class RedisConfigService implements ConfigService, ConfigManager {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisConfigService.class);
    
    private final RedissonClient redissonClient;
    private final ConfigServiceConfig config;
    private volatile boolean running = false;
    
    // 监听器管理
    private final Map<String, Set<ConfigChangeListener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, RPatternTopic> subscriptions = new ConcurrentHashMap<>();
    
    // 配置历史保留数量
    private static final int MAX_HISTORY_SIZE = 10;
    
    public RedisConfigService(RedissonClient redissonClient) {
        this(redissonClient, new ConfigServiceConfig());
    }
    
    public RedisConfigService(RedissonClient redissonClient, ConfigServiceConfig config) {
        this.redissonClient = redissonClient;
        this.config = config != null ? config : new ConfigServiceConfig();
    }
    
    @Override
    public String getConfig(String dataId, String group) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        try {
            RMap<String, Object> configMap = redissonClient.getMap(
                config.getConfigKey(group, dataId));
            
            return (String) configMap.get("content");
            
        } catch (Exception e) {
            logger.error("Failed to get config {}:{}", group, dataId, e);
            throw new RuntimeException("Failed to get config", e);
        }
    }
    
    @Override
    public boolean publishConfig(String dataId, String group, String content) {
        return publishConfig(dataId, group, content, null);
    }
    
    @Override
    public boolean publishConfig(String dataId, String group, String content, String description) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        try {
            String configKey = config.getConfigKey(group, dataId);
            RMap<String, Object> configMap = redissonClient.getMap(configKey);
            
            // 获取旧配置用于历史记录
            String oldContent = (String) configMap.get("content");
            String oldVersion = (String) configMap.get("version");
            
            // 生成新版本号
            String newVersion = generateVersion();
            LocalDateTime now = LocalDateTime.now();
            
            // 保存历史记录
            if (oldContent != null) {
                saveConfigHistory(dataId, group, oldContent, oldVersion, now);
            }
            
            // 更新配置
            Map<String, Object> configData = new HashMap<>();
            configData.put("content", content);
            configData.put("version", newVersion);
            configData.put("description", description);
            configData.put("updateTime", now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            
            if (!configMap.isExists()) {
                configData.put("createTime", now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            }
            
            configMap.putAll(configData);
            
            // 发布配置变更事件
            publishConfigChangeEvent(dataId, group, content, newVersion);
            
            logger.info("Config published: {}:{}, version: {}", group, dataId, newVersion);
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to publish config {}:{}", group, dataId, e);
            return false;
        }
    }
    
    @Override
    public boolean removeConfig(String dataId, String group) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        try {
            String configKey = config.getConfigKey(group, dataId);
            RMap<String, Object> configMap = redissonClient.getMap(configKey);
            
            if (!configMap.isExists()) {
                return false;
            }
            
            // 保存删除前的历史记录
            String content = (String) configMap.get("content");
            String version = (String) configMap.get("version");
            if (content != null) {
                saveConfigHistory(dataId, group, content, version, LocalDateTime.now(), "DELETED");
            }
            
            // 删除配置
            configMap.delete();
            
            // 删除订阅者列表
            RSet<String> subscribersSet = redissonClient.getSet(
                config.getConfigSubscribersKey(group, dataId));
            subscribersSet.delete();
            
            // 发布删除事件
            publishConfigChangeEvent(dataId, group, null, null);
            
            logger.info("Config removed: {}:{}", group, dataId);
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to remove config {}:{}", group, dataId, e);
            return false;
        }
    }
    
    @Override
    public void addListener(String dataId, String group, ConfigChangeListener listener) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        String listenerKey = group + ":" + dataId;
        
        // 添加监听器
        listeners.computeIfAbsent(listenerKey, k -> ConcurrentHashMap.newKeySet()).add(listener);
        
        // 如果是第一个监听器，创建Redis订阅
        if (!subscriptions.containsKey(listenerKey)) {
            RPatternTopic topic = redissonClient.getPatternTopic(
                config.getConfigChangeChannelKey(group, dataId));
            
            topic.addListener(Map.class, (pattern, channel, message) -> {
                handleConfigChangeEvent(dataId, group, message);
            });
            
            subscriptions.put(listenerKey, topic);
            
            // 添加到订阅者列表
            RSet<String> subscribersSet = redissonClient.getSet(
                config.getConfigSubscribersKey(group, dataId));
            subscribersSet.add(generateClientId());
            
            logger.info("Added config listener for: {}:{}", group, dataId);
        }
        
        // 立即通知当前配置
        try {
            String currentConfig = getConfig(dataId, group);
            if (currentConfig != null) {
                RMap<String, Object> configMap = redissonClient.getMap(
                    config.getConfigKey(group, dataId));
                String version = (String) configMap.get("version");
                listener.onConfigChange(dataId, group, currentConfig, version);
            }
        } catch (Exception e) {
            logger.warn("Failed to notify current config for: {}:{}", group, dataId, e);
        }
    }
    
    @Override
    public void removeListener(String dataId, String group, ConfigChangeListener listener) {
        String listenerKey = group + ":" + dataId;
        
        Set<ConfigChangeListener> configListeners = listeners.get(listenerKey);
        if (configListeners != null) {
            configListeners.remove(listener);
            
            // 如果没有监听器了，取消Redis订阅
            if (configListeners.isEmpty()) {
                listeners.remove(listenerKey);
                
                RPatternTopic topic = subscriptions.remove(listenerKey);
                if (topic != null) {
                    topic.removeAllListeners();
                    logger.info("Removed config listener for: {}:{}", group, dataId);
                }
                
                // 从订阅者列表中移除
                RSet<String> subscribersSet = redissonClient.getSet(
                    config.getConfigSubscribersKey(group, dataId));
                subscribersSet.clear(); // 简化实现，清空所有订阅者
            }
        }
    }
    
    @Override
    public List<ConfigHistory> getConfigHistory(String dataId, String group, int size) {
        if (!running) {
            throw new IllegalStateException("ConfigService is not running");
        }
        
        try {
            RList<Map<String, Object>> historyList = redissonClient.getList(
                config.getConfigHistoryKey(group, dataId));
            
            int actualSize = Math.min(size, historyList.size());
            List<Map<String, Object>> historyData = historyList.range(0, actualSize - 1);
            
            return historyData.stream()
                    .map(this::buildConfigHistory)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            logger.error("Failed to get config history for {}:{}", group, dataId, e);
            return Collections.emptyList();
        }
    }
    
    @Override
    public void start() {
        if (running) {
            return;
        }
        
        running = true;
        logger.info("RedisConfigService started");
    }
    
    @Override
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        
        // 清理所有订阅
        for (Map.Entry<String, RPatternTopic> entry : subscriptions.entrySet()) {
            try {
                entry.getValue().removeAllListeners();
            } catch (Exception e) {
                logger.warn("Failed to cleanup config subscription for: {}", entry.getKey(), e);
            }
        }
        
        subscriptions.clear();
        listeners.clear();
        
        logger.info("RedisConfigService stopped");
    }
    
    @Override
    public boolean isRunning() {
        return running;
    }
    
    /**
     * 生成配置版本号
     */
    private String generateVersion() {
        return String.valueOf(System.currentTimeMillis());
    }
    
    /**
     * 生成客户端ID
     */
    private String generateClientId() {
        return "client-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
    }
    
    /**
     * 保存配置历史记录
     */
    private void saveConfigHistory(String dataId, String group, String content, String version, 
                                 LocalDateTime changeTime) {
        saveConfigHistory(dataId, group, content, version, changeTime, "UPDATED");
    }
    
    /**
     * 保存配置历史记录
     */
    private void saveConfigHistory(String dataId, String group, String content, String version, 
                                 LocalDateTime changeTime, String operation) {
        try {
            RList<Map<String, Object>> historyList = redissonClient.getList(
                config.getConfigHistoryKey(group, dataId));
            
            Map<String, Object> historyRecord = new HashMap<>();
            historyRecord.put("dataId", dataId);
            historyRecord.put("group", group);
            historyRecord.put("content", content);
            historyRecord.put("version", version);
            historyRecord.put("operation", operation);
            historyRecord.put("changeTime", changeTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            historyRecord.put("operator", "system");

            // 添加到列表头部 (Java 17 compatible)
            historyList.add(0, historyRecord);
            
            // 保持历史记录数量限制
            if (historyList.size() > MAX_HISTORY_SIZE) {
                historyList.trim(0, MAX_HISTORY_SIZE - 1);
            }
            
        } catch (Exception e) {
            logger.warn("Failed to save config history for {}:{}", group, dataId, e);
        }
    }
    
    /**
     * 发布配置变更事件
     */
    private void publishConfigChangeEvent(String dataId, String group, String content, String version) {
        try {
            RTopic topic = redissonClient.getTopic(config.getConfigChangeChannelKey(group, dataId));
            
            Map<String, Object> changeEvent = new HashMap<>();
            changeEvent.put("dataId", dataId);
            changeEvent.put("group", group);
            changeEvent.put("content", content);
            changeEvent.put("version", version);
            changeEvent.put("timestamp", System.currentTimeMillis());
            
            topic.publish(changeEvent);
            
        } catch (Exception e) {
            logger.warn("Failed to publish config change event for {}:{}", group, dataId, e);
        }
    }
    
    /**
     * 处理配置变更事件
     */
    private void handleConfigChangeEvent(String dataId, String group, Map<String, Object> message) {
        try {
            String content = (String) message.get("content");
            String version = (String) message.get("version");
            
            String listenerKey = group + ":" + dataId;
            Set<ConfigChangeListener> configListeners = listeners.get(listenerKey);
            
            if (configListeners != null && !configListeners.isEmpty()) {
                for (ConfigChangeListener listener : configListeners) {
                    try {
                        listener.onConfigChange(dataId, group, content, version);
                    } catch (Exception e) {
                        logger.error("Error in config change listener", e);
                    }
                }
            }
            
            logger.debug("Processed config change event: {}:{}, version: {}", group, dataId, version);
            
        } catch (Exception e) {
            logger.error("Failed to handle config change event for {}:{}", group, dataId, e);
        }
    }
    
    /**
     * 构建配置历史对象
     */
    private ConfigHistory buildConfigHistory(Map<String, Object> data) {
        try {
            String dataId = (String) data.get("dataId");
            String group = (String) data.get("group");
            String content = (String) data.get("content");
            String version = (String) data.get("version");
            String operation = (String) data.get("operation");
            String operator = (String) data.get("operator");
            Object changeTimeObj = data.get("changeTime");
            
            LocalDateTime changeTime = null;
            if (changeTimeObj instanceof Long) {
                changeTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli((Long) changeTimeObj), ZoneId.systemDefault());
            }
            
            return ConfigHistory.builder()
                    .dataId(dataId)
                    .group(group)
                    .content(content)
                    .version(version)
                    .description(operation)
                    .operator(operator)
                    .changeTime(changeTime)
                    .build();
                    
        } catch (Exception e) {
            logger.error("Failed to build config history from data: {}", data, e);
            return null;
        }
    }
}