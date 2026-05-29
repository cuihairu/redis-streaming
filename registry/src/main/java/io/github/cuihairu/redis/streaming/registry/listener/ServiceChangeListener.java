package io.github.cuihairu.redis.streaming.registry.listener;

import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import java.util.List;

/**
 * Service change listener
 * Listens for service instance addition, removal, and update events
 */
public interface ServiceChangeListener {

    /**
     * Service instance change event
     *
     * @param serviceName the service name
     * @param action the change action
     * @param instance the changed service instance
     * @param allInstances all current service instances
     */
    void onServiceChange(String serviceName, ServiceChangeAction action, ServiceInstance instance, List<ServiceInstance> allInstances);
}