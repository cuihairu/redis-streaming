package io.github.cuihairu.streaming.config.impl;

import io.github.cuihairu.streaming.config.ConfigCenter;
import io.github.cuihairu.streaming.config.ConfigChangeListener;
import io.github.cuihairu.streaming.config.ConfigHistory;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于Redis的配置中心实现
 * 整合配置管理功能，提供统一的配置访问入口
 */
public class RedisConfigCenter implements ConfigCenter {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisConfigCenter.class);
    
    private final RedisConfigService configService;
    
    public RedisConfigCenter(RedissonClient redissonClient) {
        this.configService = new RedisConfigService(redissonClient);
    }
    
    @Override
    public boolean publishConfig(String dataId, String group, String content) {
        return configService.publishConfig(dataId, group, content);
    }
    
    @Override
    public boolean publishConfig(String dataId, String group, String content, String description) {
        return configService.publishConfig(dataId, group, content, description);
    }
    
    @Override
    public boolean removeConfig(String dataId, String group) {
        return configService.removeConfig(dataId, group);
    }
    
    @Override
    public List<ConfigHistory> getConfigHistory(String dataId, String group, int size) {
        return configService.getConfigHistory(dataId, group, size);
    }
    
    @Override
    public String getConfig(String dataId, String group) {
        return configService.getConfig(dataId, group);
    }
    
    @Override
    public void addListener(String dataId, String group, ConfigChangeListener listener) {
        configService.addListener(dataId, group, listener);
    }
    
    @Override
    public void removeListener(String dataId, String group, ConfigChangeListener listener) {
        configService.removeListener(dataId, group, listener);
    }
    
    @Override
    public void start() {
        configService.start();
        logger.info("RedisConfigCenter started");
    }
    
    @Override
    public void stop() {
        configService.stop();
        logger.info("RedisConfigCenter stopped");
    }
    
    @Override
    public boolean isRunning() {
        return configService.isRunning();
    }
    
    @Override
    public boolean hasConfig(String dataId, String group) {
        try {
            return configService.getConfig(dataId, group) != null;
        } catch (Exception e) {
            logger.warn("Failed to check config existence: {}:{}", group, dataId, e);
            return false;
        }
    }
    
    @Override
    public ConfigMetadata getConfigMetadata(String dataId, String group) {
        // 实现获取配置元数据的逻辑
        // 这里简化实现，实际应该从Redis中获取详细信息
        return new ConfigMetadata() {
            @Override
            public String getVersion() {
                return "1.0";
            }
            
            @Override
            public String getDescription() {
                return "Configuration for " + group + ":" + dataId;
            }
            
            @Override
            public long getCreateTime() {
                return System.currentTimeMillis();
            }
            
            @Override
            public long getLastModified() {
                return System.currentTimeMillis();
            }
            
            @Override
            public long getSize() {
                String content = getConfig(dataId, group);
                return content != null ? content.length() : 0;
            }
        };
    }
}