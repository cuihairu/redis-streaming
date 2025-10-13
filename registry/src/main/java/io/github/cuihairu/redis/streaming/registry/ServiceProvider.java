package io.github.cuihairu.redis.streaming.registry;

import java.util.List;

/**
 * Service Provider interface
 * Represents the role of a service provider in the Nacos architecture
 * 
 * Service providers are responsible for:
 * 1. Registering service instances with the registry
 * 2. Sending heartbeats to maintain instance health status
 * 3. Deregistering when shutting down
 */
public interface ServiceProvider {
    
    /**
     * Register a service instance with the registry
     * 
     * @param instance the service instance to register
     * @throws IllegalStateException if the provider is not running
     * @throws IllegalArgumentException if the instance is invalid
     */
    void register(ServiceInstance instance);
    
    /**
     * Deregister a service instance from the registry
     * 
     * @param instance the service instance to deregister
     * @throws IllegalStateException if the provider is not running
     */
    void deregister(ServiceInstance instance);
    
    /**
     * Send a heartbeat for a service instance to maintain its health status
     * 
     * @param instance the service instance to send heartbeat for
     */
    void sendHeartbeat(ServiceInstance instance);
    
    /**
     * Send heartbeats for multiple service instances in batch
     * 
     * @param instances the list of service instances to send heartbeats for
     */
    void batchSendHeartbeats(List<ServiceInstance> instances);
    
    /**
     * Start the service provider
     */
    void start();
    
    /**
     * Stop the service provider
     */
    void stop();
    
    /**
     * Check if the service provider is running
     * 
     * @return true if running, false otherwise
     */
    boolean isRunning();
}