package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigCenter;
import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.ConfigInfo;
import io.github.cuihairu.redis.streaming.config.ConfigHistory;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于Redis的配置中心实现
 * 整合配置管理功能，提供统一的配置访问入口
 */
public class RedisConfigCenter implements ConfigCenter {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisConfigCenter.class);
    
    private final RedissonClient redissonClient;
    private final ConfigServiceConfig config;
    private final RedisConfigService configService;
    
    public RedisConfigCenter(RedissonClient redissonClient) {
        this(redissonClient, new ConfigServiceConfig());
    }

    public RedisConfigCenter(RedissonClient redissonClient, ConfigServiceConfig config) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.config = config != null ? config : new ConfigServiceConfig();
        this.configService = new RedisConfigService(redissonClient, this.config);
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
        if (!isRunning()) {
            throw new IllegalStateException("ConfigCenter is not running");
        }

        ConfigInfo info = null;
        try {
            String key = config.getConfigKey(group, dataId);
            RMap<String, String> map = redissonClient.getMap(key, StringCodec.INSTANCE);
            Map<String, String> all = map.readAllMap();
            info = ConfigEntryCodec.parseInfo(dataId, group, all);
        } catch (Exception e) {
            logger.warn("Failed to get config metadata from redis for {}:{}", group, dataId, e);
        }

        ConfigInfo finalInfo = info != null ? info : ConfigInfo.builder().dataId(dataId).group(group).build();
        return new ConfigMetadata() {
            @Override
            public String getVersion() {
                return finalInfo.getVersion();
            }

            @Override
            public String getDescription() {
                return finalInfo.getDescription();
            }

            @Override
            public long getCreateTime() {
                if (finalInfo.getCreateTime() == null) return 0L;
                return finalInfo.getCreateTime()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            }

            @Override
            public long getLastModified() {
                if (finalInfo.getUpdateTime() == null) return 0L;
                return finalInfo.getUpdateTime()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            }

            @Override
            public long getSize() {
                String content = finalInfo.getContent();
                if (content == null) return 0L;
                return content.getBytes(StandardCharsets.UTF_8).length;
            }
        };
    }
}
