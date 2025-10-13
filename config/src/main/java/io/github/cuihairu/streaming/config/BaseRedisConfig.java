package io.github.cuihairu.streaming.config;

import lombok.Getter;
import lombok.Setter;

/**
 * Redis配置中心基础配置
 * 包含配置中心共享的配置设置
 */
@Getter
public class BaseRedisConfig {

    /**
     * 默认的Redis键前缀
     */
    public static final String DEFAULT_KEY_PREFIX = "redis_streaming";

    /**
     * Redis键前缀，用于避免键冲突
     */
    private String keyPrefix = DEFAULT_KEY_PREFIX;

    /**
     * 是否启用键前缀
     */
    @Setter
    private boolean enableKeyPrefix = true;

    public BaseRedisConfig() {
    }

    public BaseRedisConfig(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public BaseRedisConfig(String keyPrefix, boolean enableKeyPrefix) {
        this.keyPrefix = keyPrefix;
        this.enableKeyPrefix = enableKeyPrefix;
    }

    /**
     * 设置Redis键前缀
     *
     * @param keyPrefix 键前缀
     */
    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
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
