package io.github.cuihairu.redis.streaming.registry;

import lombok.Getter;
import lombok.Setter;

/**
 * Service provider Redis configuration
 * Contains Redis key patterns and configuration specific to service providers
 */
@Setter
@Getter
public class ServiceProviderConfig extends BaseRedisConfig {

    /**
     * Heartbeat timeout in seconds (default 90 seconds)
     * -- GETTER --
     *  Get the heartbeat timeout in seconds
     * -- SETTER --
     *  Set the heartbeat timeout in seconds
     */
    private int heartbeatTimeoutSeconds = 90;

    public ServiceProviderConfig() {
        super();
    }

    public ServiceProviderConfig(String keyPrefix) {
        super(keyPrefix);
    }

    public ServiceProviderConfig(String keyPrefix, boolean enableKeyPrefix) {
        super(keyPrefix, enableKeyPrefix);
    }
    /**
     * Get the service instance key
     *
     * @param serviceName the service name
     * @param instanceId the instance ID
     * @return the service instance key
     */
    public String getServiceInstanceKey(String serviceName, String instanceId) {
        return getRegistryKeys().getServiceInstanceKey(serviceName, instanceId);
    }

    /**
     * Get the service instances list key
     *
     * @param serviceName the service name
     * @return the service instances list key
     */
    public String getServiceInstancesKey(String serviceName) {
        return getRegistryKeys().getServiceHeartbeatsKey(serviceName);
    }

    /**
     * Get the heartbeat key
     *
     * @param serviceName the service name
     * @return the heartbeat key
     */
    public String getHeartbeatKey(String serviceName) {
        return getRegistryKeys().getServiceHeartbeatsKey(serviceName);
    }

    /**
     * Get the service change channel key
     *
     * @param serviceName the service name
     * @return the service change channel key
     */
    public String getServiceChangeChannelKey(String serviceName) {
        return getRegistryKeys().getServiceChangeChannelKey(serviceName);
    }
}