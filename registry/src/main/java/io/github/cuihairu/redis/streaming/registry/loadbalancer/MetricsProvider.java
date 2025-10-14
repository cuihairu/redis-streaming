package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import java.util.Map;

/**
 * Provides metrics for a given service instance.
 */
public interface MetricsProvider {
    Map<String, Object> getMetrics(String serviceName, String instanceId);
}

