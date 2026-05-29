package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;

/**
 * Custom health checker
 * Allows users to implement custom health check logic
 */
public abstract class CustomHealthChecker implements HealthChecker {
    
    @Override
    public boolean check(ServiceInstance serviceInstance) throws Exception {
        // First perform basic TCP connection check
        if (!isPortReachable(serviceInstance)) {
            return false;
        }
        
        // Then perform custom business health check
        return doCheck(serviceInstance);
    }
    
    /**
     * Perform custom business health check
     * Subclasses need to implement this method to provide specific health check logic
     *
     * @param serviceInstance the service instance
     * @return true if healthy, false if unhealthy
     * @throws Exception exception that may be thrown during the check
     */
    protected abstract boolean doCheck(ServiceInstance serviceInstance) throws Exception;
    
    /**
     * Check if port is reachable
     */
    private boolean isPortReachable(ServiceInstance serviceInstance) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(
                new java.net.InetSocketAddress(
                    serviceInstance.getHost(), 
                    serviceInstance.getPort()
                ), 
                3000 // 3 second timeout
            );
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }
}