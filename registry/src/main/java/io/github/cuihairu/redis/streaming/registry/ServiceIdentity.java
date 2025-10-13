package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.core.utils.SystemUtils;

/**
 * 服务标识接口
 * 明确定义服务的唯一标识，包含服务名称和服务实例ID
 */
public interface ServiceIdentity {
    /**
     * 获取服务名称
     * 服务名称是服务的逻辑标识，同一服务的所有实例共享相同的服务名称
     */
    String getServiceName();

    /**
     * 获取实例ID
     * 实例ID是服务实例的唯一标识，在同一服务中每个实例的ID必须唯一,默认为主机名
     */
    default String getInstanceId() {
        return SystemUtils.getLocalHostname();
    }

    /**
     * 获取全局唯一ID
     * 格式为 ServiceName:InstanceId，在整个注册中心中唯一标识一个服务实例
     *
     * @return 全局唯一标识符，格式为 "serviceName:instanceId"，如果任一部分为null则返回null
     */
    default String getUniqueId() {
        String serviceName = getServiceName();
        String instanceId = getInstanceId();
        if (serviceName == null || instanceId == null) {
            return null;
        }
        return serviceName + ":" + instanceId;
    }

    /**
     * 验证服务标识是否有效
     */
    default boolean isValidIdentity() {
        String serviceName = getServiceName();
        String instanceId = getInstanceId();
        return serviceName != null && instanceId != null  && !serviceName.trim().isEmpty() && !instanceId.trim().isEmpty();
    }
}