package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.core.utils.SystemUtils;

/**
 * Service identity interface
 * Defines the unique identity of a service, including service name and instance ID
 */
public interface ServiceIdentity {
    /**
     * Get the service name
     * The service name is the logical identifier of a service; all instances of the same service share the same service name
     */
    String getServiceName();

    /**
     * Get the instance ID
     * The instance ID is the unique identifier of a service instance; each instance must have a unique ID within the same service, defaults to hostname
     */
    default String getInstanceId() {
        return SystemUtils.getLocalHostname();
    }

    /**
     * Get the globally unique ID
     * Format is ServiceName:InstanceId, uniquely identifies a service instance across the entire registry
     *
     * @return globally unique identifier in the format "serviceName:instanceId", or null if either part is null
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
     * Validate whether the service identity is valid
     */
    default boolean isValidIdentity() {
        String serviceName = getServiceName();
        String instanceId = getInstanceId();
        return serviceName != null && instanceId != null  && !serviceName.trim().isEmpty() && !instanceId.trim().isEmpty();
    }
}