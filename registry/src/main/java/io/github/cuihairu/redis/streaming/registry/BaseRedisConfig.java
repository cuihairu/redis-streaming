package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.keys.RegistryKeys;
import lombok.Getter;
import lombok.Setter;

/**
 * Redis registry base configuration.
 *
 * <p>Extends the shared key-prefix handling of
 * {@link io.github.cuihairu.redis.streaming.config.BaseRedisConfig} (the config module was
 * split out of the registry module) and adds the registry-specific {@link RegistryKeys}
 * manager.</p>
 */
@Getter
@Setter
public class BaseRedisConfig extends io.github.cuihairu.redis.streaming.config.BaseRedisConfig {

    /**
     * Default Redis key prefix
     */
    public static final String DEFAULT_KEY_PREFIX = "redis_streaming_registry";

    /**
     * Unified key manager
     * -- GETTER --
     *  Get the unified key manager
     */
    private RegistryKeys registryKeys;

    /**
     * Tenant key segment. {@code default} yields keys byte-identical to the
     * legacy layout; any other tenant inserts a {@code :tenant} segment after
     * the key prefix.
     */
    private String tenant = RegistryKeys.DEFAULT_TENANT;

    public BaseRedisConfig() {
        super(DEFAULT_KEY_PREFIX);
        this.registryKeys = new RegistryKeys(getKeyPrefix(), tenant);
    }

    public BaseRedisConfig(String keyPrefix) {
        super(keyPrefix);
        this.registryKeys = new RegistryKeys(keyPrefix, tenant);
    }

    public BaseRedisConfig(String keyPrefix, boolean enableKeyPrefix) {
        super(keyPrefix, enableKeyPrefix);
        this.registryKeys = new RegistryKeys(keyPrefix, tenant);
    }

    public BaseRedisConfig(String keyPrefix, boolean enableKeyPrefix, String tenant) {
        super(keyPrefix, enableKeyPrefix);
        this.registryKeys = new RegistryKeys(keyPrefix, tenant);
        this.tenant = RegistryKeys.normalizeTenant(tenant);
    }

    /**
     * Set the Redis key prefix
     *
     * @param keyPrefix the key prefix
     */
    @Override
    public void setKeyPrefix(String keyPrefix) {
        super.setKeyPrefix(keyPrefix);
        this.registryKeys = new RegistryKeys(keyPrefix, tenant); // Recreate RegistryKeys
    }

    /**
     * Set the tenant key segment and recreate the key manager.
     */
    public void setTenant(String tenant) {
        this.tenant = RegistryKeys.normalizeTenant(tenant);
        this.registryKeys = new RegistryKeys(getKeyPrefix(), this.tenant);
    }
}
