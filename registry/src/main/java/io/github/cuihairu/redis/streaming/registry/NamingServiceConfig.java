package io.github.cuihairu.redis.streaming.registry;

import java.util.concurrent.TimeUnit;

/**
 * NamingService configuration that combines both provider and consumer configurations
 * Used by RedisNamingService to provide unified configuration for both roles
 */
public class NamingServiceConfig extends BaseRedisConfig {

    /**
     * 是否启用健康检测（默认关闭）
     * 适用于消费者角色
     */
    private boolean enableHealthCheck = false;

    /**
     * 健康检测间隔（默认30秒）
     */
    private long healthCheckInterval = 30;

    /**
     * 健康检测时间单位
     */
    private TimeUnit healthCheckTimeUnit = TimeUnit.SECONDS;

    /**
     * 健康检测超时时间（毫秒，默认5秒）
     */
    private int healthCheckTimeout = 5000;

    /**
     * 是否启用Admin管理功能（默认开启）
     * Admin功能允许对注册中心的服务进行管理操作，包括：
     * - 手动下线服务实例
     * - 修改服务实例状态
     * - 查询服务实例详细信息
     * - 调整服务实例权重等
     *
     * NamingService同时扮演提供者和消费者角色，此配置主要控制消费者端的管理能力
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

    // ==================== 健康检测配置 ====================

    /**
     * 是否启用健康检测
     *
     * @return 如果启用返回true，否则返回false
     */
    public boolean isEnableHealthCheck() {
        return enableHealthCheck;
    }

    /**
     * 设置是否启用健康检测
     *
     * @param enableHealthCheck 是否启用健康检测
     */
    public void setEnableHealthCheck(boolean enableHealthCheck) {
        this.enableHealthCheck = enableHealthCheck;
    }

    /**
     * 获取健康检测间隔
     *
     * @return 健康检测间隔
     */
    public long getHealthCheckInterval() {
        return healthCheckInterval;
    }

    /**
     * 设置健康检测间隔
     *
     * @param healthCheckInterval 健康检测间隔
     */
    public void setHealthCheckInterval(long healthCheckInterval) {
        this.healthCheckInterval = healthCheckInterval;
    }

    /**
     * 获取健康检测时间单位
     *
     * @return 健康检测时间单位
     */
    public TimeUnit getHealthCheckTimeUnit() {
        return healthCheckTimeUnit;
    }

    /**
     * 设置健康检测时间单位
     *
     * @param healthCheckTimeUnit 健康检测时间单位
     */
    public void setHealthCheckTimeUnit(TimeUnit healthCheckTimeUnit) {
        this.healthCheckTimeUnit = healthCheckTimeUnit;
    }

    /**
     * 获取健康检测超时时间（毫秒）
     *
     * @return 健康检测超时时间
     */
    public int getHealthCheckTimeout() {
        return healthCheckTimeout;
    }

    /**
     * 设置健康检测超时时间（毫秒）
     *
     * @param healthCheckTimeout 健康检测超时时间
     */
    public void setHealthCheckTimeout(int healthCheckTimeout) {
        this.healthCheckTimeout = healthCheckTimeout;
    }

    // ==================== Admin管理功能配置 ====================

    /**
     * 是否启用Admin管理功能
     *
     * @return 如果启用返回true，否则返回false
     */
    public boolean isEnableAdminService() {
        return enableAdminService;
    }

    /**
     * 设置是否启用Admin管理功能
     *
     * @param enableAdminService 是否启用Admin管理功能
     */
    public void setEnableAdminService(boolean enableAdminService) {
        this.enableAdminService = enableAdminService;
    }

    // ==================== Redis键管理方法 ====================
    
    /**
     * 获取服务实例键
     *
     * @param serviceName 服务名称
     * @param instanceId 实例ID
     * @return 服务实例键
     */
    public String getServiceInstanceKey(String serviceName, String instanceId) {
        return getRegistryKeys().getServiceInstanceKey(serviceName, instanceId);
    }

    /**
     * 获取服务实例列表键
     *
     * @param serviceName 服务名称
     * @return 服务实例列表键
     */
    public String getServiceInstancesKey(String serviceName) {
        return getRegistryKeys().getServiceHeartbeatsKey(serviceName);
    }

    /**
     * 获取心跳键
     *
     * @param serviceName 服务名称
     * @return 心跳键
     */
    public String getHeartbeatKey(String serviceName) {
        return getRegistryKeys().getServiceHeartbeatsKey(serviceName);
    }

    /**
     * 获取服务变更通道键
     *
     * @param serviceName 服务名称
     * @return 服务变更通道键
     */
    public String getServiceChangeChannelKey(String serviceName) {
        return getRegistryKeys().getServiceChangeChannelKey(serviceName);
    }
}