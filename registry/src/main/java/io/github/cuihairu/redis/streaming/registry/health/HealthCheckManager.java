package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Health check manager
 * Manages health checkers for multiple service instances
 */
public class HealthCheckManager {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckManager.class);
    
    private final ConcurrentHashMap<String, ClientHealthChecker> healthCheckers;
    private final ConcurrentHashMap<Protocol, HealthChecker> protocolHealthCheckers;
    private final HealthChecker defaultHealthChecker;
    private final BiConsumer<String, Boolean> healthStatusReporter;
    private final long checkInterval;
    private final TimeUnit timeUnit;
    
    public HealthCheckManager(HealthChecker defaultHealthChecker,
                             BiConsumer<String, Boolean> healthStatusReporter,
                             long checkInterval,
                             TimeUnit timeUnit) {
        this.healthCheckers = new ConcurrentHashMap<>();
        this.protocolHealthCheckers = new ConcurrentHashMap<>();
        this.defaultHealthChecker = defaultHealthChecker;
        this.healthStatusReporter = healthStatusReporter;
        this.checkInterval = checkInterval;
        this.timeUnit = timeUnit;
    }
    
    /**
     * Register a health checker for the specified protocol
     */
    public void registerProtocolHealthChecker(Protocol protocol, HealthChecker healthChecker) {
        protocolHealthCheckers.put(protocol, healthChecker);
        logger.info("Registered health checker for protocol: {}", protocol.getName());
    }
    
    /**
     * Get the health checker for the specified protocol
     */
    public HealthChecker getProtocolHealthChecker(Protocol protocol) {
        return protocolHealthCheckers.get(protocol);
    }
    
    /**
     * Register a health checker for a service instance
     */
    public void registerServiceInstance(ServiceInstance serviceInstance) {
        String uniqueId = serviceInstance.getUniqueId();

        if (healthCheckers.containsKey(uniqueId)) {
            logger.warn("Health checker already registered for instance: {}", uniqueId);
            return;
        }

        // Get the corresponding health checker based on protocol
        Protocol protocol =  serviceInstance.getProtocol();
        HealthChecker healthChecker = protocolHealthCheckers.get(protocol);
        if (healthChecker == null) {
            healthChecker = defaultHealthChecker;
        }

        // If the default checker is also null, return directly
        if (healthChecker == null) {
            logger.warn("No health checker registered for protocol: {}", protocol.getName());
            return;
        }

        ClientHealthChecker clientHealthChecker = new ClientHealthChecker(
            serviceInstance,
            healthChecker,
            isHealthy -> healthStatusReporter.accept(uniqueId, isHealthy),
            checkInterval,
            timeUnit
        );

        healthCheckers.put(uniqueId, clientHealthChecker);
        clientHealthChecker.start();
        logger.info("Registered health checker for service instance: {} with protocol: {}",
                   uniqueId, protocol.getName());
    }
    
    /**
     * Remove the health checker for a service instance
     * @param uniqueId globally unique ID of the service instance (serviceName:instanceId)
     */
    public void unregisterServiceInstance(String uniqueId) {
        ClientHealthChecker healthChecker = healthCheckers.remove(uniqueId);
        if (healthChecker != null) {
            healthChecker.stop();
            logger.info("Unregistered health checker for service instance: {}", uniqueId);
        }
    }
    
    /**
     * Remove the health checker for a service instance
     */
    public void unregisterServiceInstance(ServiceInstance serviceInstance) {
        unregisterServiceInstance(serviceInstance.getUniqueId());
    }
    
    /**
     * Start all health checkers
     */
    public void startAll() {
        healthCheckers.values().forEach(ClientHealthChecker::start);
        logger.info("Started all health checkers, count: {}", healthCheckers.size());
    }
    
    /**
     * Stop all health checkers
     */
    public void stopAll() {
        healthCheckers.values().forEach(ClientHealthChecker::stop);
        healthCheckers.clear();
        logger.info("Stopped all health checkers");
    }
    
    /**
     * Get the number of health checkers
     */
    public int getHealthCheckerCount() {
        return healthCheckers.size();
    }
    
    /**
     * Check the health status of a specified instance
     * @param uniqueId globally unique ID of the service instance (serviceName:instanceId)
     */
    public boolean isInstanceHealthy(String uniqueId) {
        ClientHealthChecker healthChecker = healthCheckers.get(uniqueId);
        return healthChecker != null && healthChecker.getLastHealthStatus();
    }
}