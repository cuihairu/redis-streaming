package io.github.cuihairu.redis.streaming.registry;

import java.util.List;

/**
 * Service Registry interface
 * Represents the technical perspective of service registration operations
 *
 * <p>Comparison with ServiceProvider:</p>
 * <ul>
 *   <li><b>ServiceRegistry</b>: Technical operations perspective - focuses on registry operations</li>
 *   <li><b>ServiceProvider</b>: Business role perspective - represents the service provider role</li>
 * </ul>
 *
 * <p>This interface provides registry operations including:</p>
 * <ul>
 *   <li>Registering service instances</li>
 *   <li>Deregistering service instances</li>
 *   <li>Maintaining instance health status via heartbeats</li>
 * </ul>
 *
 * <p>Design symmetry:</p>
 * <pre>
 * Technical Perspective:        Business Role Perspective:
 * - ServiceRegistry      ←→     ServiceProvider
 * - ServiceDiscovery     ←→     ServiceConsumer
 * </pre>
 */
public interface ServiceRegistry {

    /**
     * Register a service instance with the registry
     *
     * @param instance the service instance to register
     * @throws IllegalStateException if the registry is not running
     * @throws IllegalArgumentException if the instance is invalid
     */
    void register(ServiceInstance instance);

    /**
     * Deregister a service instance from the registry
     *
     * @param instance the service instance to deregister
     * @throws IllegalStateException if the registry is not running
     */
    void deregister(ServiceInstance instance);

    /**
     * Send a heartbeat for a service instance to maintain its health status
     *
     * @param instance the service instance to send heartbeat for
     */
    void heartbeat(ServiceInstance instance);

    /**
     * Send heartbeats for multiple service instances in batch
     *
     * @param instances the list of service instances to send heartbeats for
     */
    void batchHeartbeat(List<ServiceInstance> instances);

    /**
     * Start the service registry
     */
    void start();

    /**
     * Stop the service registry
     */
    void stop();

    /**
     * Check if the service registry is running
     *
     * @return true if running, false otherwise
     */
    boolean isRunning();
}
