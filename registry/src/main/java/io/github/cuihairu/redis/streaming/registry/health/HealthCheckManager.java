package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Health check manager
 * Manages health checkers for multiple service instances
 *
 * <p>B-08: all checkers share one daemon-thread pool, so thread count does not grow
 * with the number of discovered instances. The pool is created on first registration
 * and its idle threads time out, an idle manager holds no threads.
 */
public class HealthCheckManager {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckManager.class);

    private static final long SHARED_POOL_KEEP_ALIVE_SECONDS = 60;

    private final ConcurrentHashMap<String, ClientHealthChecker> healthCheckers;
    private final ConcurrentHashMap<Protocol, HealthChecker> protocolHealthCheckers;
    private final HealthChecker defaultHealthChecker;
    private final BiConsumer<String, Boolean> healthStatusReporter;
    private final long checkInterval;
    private final TimeUnit timeUnit;

    private volatile ScheduledThreadPoolExecutor sharedExecutor;

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
     * The pool is deliberately smaller than the instance count: probes are I/O-bound
     * with their own connect/read timeouts, and excess due checks queue briefly rather
     * than each claiming a thread (B-08).
     */
    private synchronized ScheduledExecutorService executor() {
        ScheduledThreadPoolExecutor executor = sharedExecutor;
        if (executor == null || executor.isShutdown()) {
            AtomicInteger seq = new AtomicInteger();
            int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            executor = new ScheduledThreadPoolExecutor(poolSize, r -> {
                Thread t = new Thread(r, "health-check-manager-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            executor.setKeepAliveTime(SHARED_POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
            sharedExecutor = executor;
        }
        return executor;
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

        // Fast path only; the authoritative guard is the putIfAbsent below (B-26).
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
            timeUnit,
            executor()
        );

        // B-26: the authoritative guard must be putIfAbsent — two threads discovering the
        // same instance concurrently both passed the containsKey fast-path above, and both
        // put+started a checker; the overwritten duplicate kept probing forever because
        // unregister only stops the entry left in the map.
        ClientHealthChecker incumbent = healthCheckers.putIfAbsent(uniqueId, clientHealthChecker);
        if (incumbent != null) {
            logger.warn("Health checker already registered for instance: {}", uniqueId);
            return;
        }
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
     * Stop all health checkers and release the shared scheduler; a later registration
     * creates a fresh one.
     */
    public synchronized void stopAll() {
        healthCheckers.values().forEach(ClientHealthChecker::stop);
        healthCheckers.clear();
        ScheduledThreadPoolExecutor executor = sharedExecutor;
        sharedExecutor = null;
        if (executor != null) {
            executor.shutdown();
        }
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