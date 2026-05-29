package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.keys.RegistryKeys;
import lombok.Getter;
import lombok.Setter;

/**
 * Redis registry base configuration
 * Contains configuration settings shared by all roles
 */
@Getter
@Setter
public class BaseRedisConfig {

    /**
     * Default Redis key prefix
     */
    public static final String DEFAULT_KEY_PREFIX = "redis_streaming_registry";

    /**
     * Redis key prefix, used to avoid key conflicts
     * -- GETTER --
     *  Get the Redis key prefix
     */
    private String keyPrefix = DEFAULT_KEY_PREFIX;

    /**
     * Whether to enable key prefix
     * -- GETTER --
     *  Check if key prefix is enabled
     * -- SETTER --
     *  Set whether to enable key prefix
     */
    private boolean enableKeyPrefix = true;

    /**
     * Unified key manager
     * -- GETTER --
     *  Get the unified key manager
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
     * Set the Redis key prefix
     *
     * @param keyPrefix the key prefix
     */
    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
        this.registryKeys = new RegistryKeys(keyPrefix); // Recreate RegistryKeys
    }

    /**
     * Format a Redis key based on configuration
     *
     * @param keyPattern the key pattern
     * @param args the key arguments
     * @return the formatted key
     */
    public String formatKey(String keyPattern, Object... args) {
        if (enableKeyPrefix && keyPrefix != null && !keyPrefix.isEmpty()) {
            return keyPrefix + ":" + String.format(keyPattern, args);
        } else {
            return String.format(keyPattern, args);
        }
    }
}