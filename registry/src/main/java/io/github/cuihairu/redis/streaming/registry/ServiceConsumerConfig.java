package io.github.cuihairu.redis.streaming.registry;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.TimeUnit;

/**
 * 服务消费者Redis配置
 * 包含服务消费者专用的Redis键模式和配置
 */
@Setter
@Getter
public class ServiceConsumerConfig extends BaseRedisConfig {

    /**
     * 是否启用健康检测（默认关闭）
     * 当启用时，消费者会主动检测所发现服务的健康状态
     * -- GETTER --
     *  是否启用健康检测
     * -- SETTER --
     *  设置是否启用健康检测
     *
     */
    private boolean enableHealthCheck = false;

    /**
     * 健康检测间隔（默认30秒）
     * -- GETTER --
     *  获取健康检测间隔
     * -- SETTER --
     *  设置健康检测间隔
     */
    private long healthCheckInterval = 30;

    /**
     * 健康检测时间单位
     * -- GETTER --
     *  获取健康检测时间单位
     * -- SETTER --
     *  设置健康检测时间单位
     */
    private TimeUnit healthCheckTimeUnit = TimeUnit.SECONDS;

    /**
     * 健康检测超时时间（毫秒，默认5秒）
     * -- GETTER --
     *  获取健康检测超时时间（毫秒）
     * -- SETTER --
     *  设置健康检测超时时间（毫秒）
     */
    private int healthCheckTimeout = 5000;

    /**
     * 心跳超时时间（秒，默认90秒）
     * -- GETTER --
     *  获取心跳超时时间（秒）
     * -- SETTER --
     *  设置心跳超时时间（秒）
     */
    private int heartbeatTimeoutSeconds = 90;

    /**
     * 是否启用Admin管理功能（默认开启）
     * Admin功能允许消费者对注册中心的服务进行管理操作，包括：
     * - 手动下线服务实例
     * - 修改服务实例状态
     * - 查询服务实例详细信息
     * - 调整服务实例权重等
     * 注意：此配置仅影响消费者端的管理能力，不涉及服务提供者
     * -- GETTER --
     *  是否启用Admin管理功能
     * -- SETTER --
     *  设置是否启用Admin管理功能
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