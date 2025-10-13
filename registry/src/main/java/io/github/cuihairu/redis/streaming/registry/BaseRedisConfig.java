package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.keys.RegistryKeys;
import lombok.Getter;
import lombok.Setter;

/**
 * Redis注册中心基础配置
 * 包含所有角色共享的配置设置
 */
@Getter
@Setter
public class BaseRedisConfig {

    /**
     * 默认的Redis键前缀
     */
    public static final String DEFAULT_KEY_PREFIX = "redis_streaming_registry";

    /**
     * Redis键前缀，用于避免键冲突
     * -- GETTER --
     *  获取Redis键前缀
     */
    private String keyPrefix = DEFAULT_KEY_PREFIX;

    /**
     * 是否启用键前缀
     * -- GETTER --
     *  检查是否启用键前缀
     * -- SETTER --
     *  设置是否启用键前缀
     */
    private boolean enableKeyPrefix = true;

    /**
     * 统一的Key管理器
     * -- GETTER --
     *  获取统一的Key管理器
     *
     */
    private RegistryKeys registryKeys;

    public BaseRedisConfig() {
        this.registryKeys = new RegistryKeys(keyPrefix);
    }

    public BaseRedisConfig(String keyPrefix) {
        this.keyPrefix = keyPrefix;
        this.registryKeys = new RegistryKeys(keyPrefix);
    }

    public BaseRedisConfig(String keyPrefix, boolean enableKeyPrefix) {
        this.keyPrefix = keyPrefix;
        this.enableKeyPrefix = enableKeyPrefix;
        this.registryKeys = new RegistryKeys(keyPrefix);
    }

    /**
     * 设置Redis键前缀
     *
     * @param keyPrefix 键前缀
     */
    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
        this.registryKeys = new RegistryKeys(keyPrefix); // 重新创建RegistryKeys
    }

    /**
     * 根据配置格式化Redis键
     *
     * @param keyPattern 键模式
     * @param args 键参数
     * @return 格式化后的键
     */
    public String formatKey(String keyPattern, Object... args) {
        if (enableKeyPrefix && keyPrefix != null && !keyPrefix.isEmpty()) {
            return keyPrefix + ":" + String.format(keyPattern, args);
        } else {
            return String.format(keyPattern, args);
        }
    }
}