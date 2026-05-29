package io.github.cuihairu.redis.streaming.registry;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.TimeUnit;

/**
 * Service consumer Redis configuration
 * Contains Redis key patterns and configuration specific to service consumers
 */
@Setter
@Getter
public class ServiceConsumerConfig extends BaseRedisConfig {

    /**
     * Whether to enable health checking (default off)
     * When enabled, the consumer actively checks the health status of discovered services
     * -- GETTER --
     *  Whether health checking is enabled
     * -- SETTER --
     *  Set whether to enable health checking
     *
     */
    private boolean enableHealthCheck = false;

    /**
     * Health check interval (default 30 seconds)
     * -- GETTER --
     *  Get the health check interval
     * -- SETTER --
     *  Set the health check interval
     */
    private long healthCheckInterval = 30;

    /**
     * Health check time unit
     * -- GETTER --
     *  Get the health check time unit
     * -- SETTER --
     *  Set the health check time unit
     */
    private TimeUnit healthCheckTimeUnit = TimeUnit.SECONDS;

    /**
     * Health check timeout in milliseconds (default 5 seconds)
     * -- GETTER --
     *  Get the health check timeout in milliseconds
     * -- SETTER --
     *  Set the health check timeout in milliseconds
     */
    private int healthCheckTimeout = 5000;

    /**
     * Heartbeat timeout in seconds (default 90 seconds)
     * -- GETTER --
     *  Get the heartbeat timeout in seconds
     * -- SETTER --
     *  Set the heartbeat timeout in seconds
     */
    private int heartbeatTimeoutSeconds = 90;

    /**
     * Whether to enable admin management features (default on)
     * Admin features allow consumers to manage services in the registry, including:
     * - Manual offline of service instances
     * - Modify service instance status
     * - Query service instance details
     * - Adjust service instance weights, etc.
     * Note: This configuration only affects consumer-side management capabilities, not service providers
     * -- GETTER --
     *  Whether admin management features are enabled
     * -- SETTER --
     *  Set whether to enable admin management features
     */
    private boolean enableAdminService = true;

    public ServiceConsumerConfig() {
        super();
    }

    public ServiceConsumerConfig(String keyPrefix) {
        super(keyPrefix);
    }

    public ServiceConsumerConfig(String keyPrefix, boolean enableKeyPrefix) {
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