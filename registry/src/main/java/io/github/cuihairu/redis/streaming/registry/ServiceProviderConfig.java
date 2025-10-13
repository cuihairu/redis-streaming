package io.github.cuihairu.redis.streaming.registry;

import lombok.Getter;
import lombok.Setter;

/**
 * 服务提供者Redis配置
 * 包含服务提供者专用的Redis键模式和配置
 */
@Setter
@Getter
public class ServiceProviderConfig extends BaseRedisConfig {

    /**
     * 心跳超时时间（秒，默认90秒）
     * -- GETTER --
     *  获取心跳超时时间（秒）
     * -- SETTER --
     *  设置心跳超时时间（秒）
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