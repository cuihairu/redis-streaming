package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

import java.util.List;
import java.util.Map;

/**
 * Load balancer SPI.
 * Implementations pick one instance from candidates based on metadata/metrics/context.
 */
public interface LoadBalancer {
    ServiceInstance choose(String serviceName,
                           List<ServiceInstance> candidates,
                           Map<String, Object> context);
}

