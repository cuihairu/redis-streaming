package io.github.cuihairu.redis.streaming.config;

import lombok.Getter;
import lombok.Setter;

/**
 * Redis configuration center base configuration
 * Contains shared configuration settings for the configuration center
 */
@Getter
public class BaseRedisConfig {

    /**
     * Default Redis key prefix
     */
    public static final String DEFAULT_KEY_PREFIX = "redis_streaming";

    /**
     * Redis key prefix, used to avoid key conflicts
     */
    private String keyPrefix = DEFAULT_KEY_PREFIX;

    /**
     * Whether to enable key prefix
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
     * Set Redis key prefix
     *
     * @param keyPrefix key prefix
     */
    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    /**
     * Format Redis key based on configuration
     *
     * @param keyPattern key pattern
     * @param args key arguments
     * @return formatted key
     */
    public String formatKey(String keyPattern, Object... args) {
        if (enableKeyPrefix && keyPrefix != null && !keyPrefix.isEmpty()) {
            return keyPrefix + ":" + String.format(keyPattern, args);
        } else {
            return String.format(keyPattern, args);
        }
    }
}
