package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

/**
 * Health checker interface
 * Defines standard methods for health checking
 */
public interface HealthChecker {
    /**
     * Check the health status of a service instance
     *
     * @param serviceInstance the service instance
     * @return true if healthy, false if unhealthy
     * @throws Exception exception that may be thrown during the check
     */
    boolean check(ServiceInstance serviceInstance) throws Exception;
}