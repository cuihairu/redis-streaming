package io.github.cuihairu.redis.streaming.registry;

import java.util.concurrent.TimeUnit;

/**
 * NamingService configuration that combines both provider and consumer configurations
 * Used by RedisNamingService to provide unified configuration for both roles
 */
public class NamingServiceConfig extends BaseRedisConfig {

    /**
     * Whether to enable health checking (default off)
     * Applicable to the consumer role
     */
    private boolean enableHealthCheck = false;

    /**
     * Health check interval (default 30 seconds)
     */
    private long healthCheckInterval = 30;

    /**
     * Health check time unit
     */
    private TimeUnit healthCheckTimeUnit = TimeUnit.SECONDS;

    /**
     * Health check timeout in milliseconds (default 5 seconds)
     */
    private int healthCheckTimeout = 5000;

    /**
     * Whether to enable admin management features (default on)
     * Admin features allow management operations on services in the registry, including:
     * - Manual offline of service instances
     * - Modify service instance status
     * - Query service instance details
     * - Adjust service instance weights, etc.
     *
     * NamingService plays both provider and consumer roles; this configuration primarily controls consumer-side management capabilities
     */
    private boolean enableAdminService = true;

    public NamingServiceConfig() {
    }

    public NamingServiceConfig(String keyPrefix) {
        setKeyPrefix(keyPrefix);
    }

    public NamingServiceConfig(String keyPrefix, boolean enableKeyPrefix) {
        setKeyPrefix(keyPrefix);
        setEnableKeyPrefix(enableKeyPrefix);
    }

    // ==================== Health check configuration ====================

    /**
     * Whether health checking is enabled
     *
     * @return true if enabled, false otherwise
     */
    public boolean isEnableHealthCheck() {
        return enableHealthCheck;
    }

    /**
     * Set whether to enable health checking
     *
     * @param enableHealthCheck whether to enable health checking
     */
    public void setEnableHealthCheck(boolean enableHealthCheck) {
        this.enableHealthCheck = enableHealthCheck;
    }

    /**
     * Get the health check interval
     *
     * @return the health check interval
     */
    public long getHealthCheckInterval() {
        return healthCheckInterval;
    }

    /**
     * Set the health check interval
     *
     * @param healthCheckInterval the health check interval
     */
    public void setHealthCheckInterval(long healthCheckInterval) {
        this.healthCheckInterval = healthCheckInterval;
    }

    /**
     * Get the health check time unit
     *
     * @return the health check time unit
     */
    public TimeUnit getHealthCheckTimeUnit() {
        return healthCheckTimeUnit;
    }

    /**
     * Set the health check time unit
     *
     * @param healthCheckTimeUnit the health check time unit
     */
    public void setHealthCheckTimeUnit(TimeUnit healthCheckTimeUnit) {
        this.healthCheckTimeUnit = healthCheckTimeUnit;
    }

    /**
     * Get the health check timeout in milliseconds
     *
     * @return the health check timeout
     */
    public int getHealthCheckTimeout() {
        return healthCheckTimeout;
    }

    /**
     * Set the health check timeout in milliseconds
     *
     * @param healthCheckTimeout the health check timeout
     */
    public void setHealthCheckTimeout(int healthCheckTimeout) {
        this.healthCheckTimeout = healthCheckTimeout;
    }

    // ==================== Admin management feature configuration ====================

    /**
     * Whether admin management features are enabled
     *
     * @return true if enabled, false otherwise
     */
    public boolean isEnableAdminService() {
        return enableAdminService;
    }

    /**
     * Set whether to enable admin management features
     *
     * @param enableAdminService whether to enable admin management features
     */
    public void setEnableAdminService(boolean enableAdminService) {
        this.enableAdminService = enableAdminService;
    }

    // ==================== Redis key management methods ====================
    
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