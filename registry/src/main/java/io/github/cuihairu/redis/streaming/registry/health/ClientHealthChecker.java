package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Client health checker
 * Responsible for probing the health status of service instances and reporting to the registry
 */
public class ClientHealthChecker {
    private static final Logger logger = LoggerFactory.getLogger(ClientHealthChecker.class);
    
    private final ServiceInstance serviceInstance;
    private final HealthChecker healthChecker;
    private final Consumer<Boolean> healthReporter;
    private final ScheduledExecutorService scheduler;
    private final long checkInterval;
    private final TimeUnit timeUnit;
    
    private volatile boolean lastHealthStatus = true;
    private volatile boolean running = false;
    
    public ClientHealthChecker(ServiceInstance serviceInstance, 
                              HealthChecker healthChecker,
                              Consumer<Boolean> healthReporter,
                              long checkInterval, 
                              TimeUnit timeUnit) {
        this.serviceInstance = serviceInstance;
        this.healthChecker = healthChecker;
        this.healthReporter = healthReporter;
        this.checkInterval = checkInterval;
        this.timeUnit = timeUnit;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "health-checker-" + serviceInstance.getInstanceId())
        );
    }
    
    /**
     * Start the health check
     */
    public void start() {
        if (running) {
            return;
        }

        running = true;
        checkHealth();
        scheduler.scheduleWithFixedDelay(this::checkHealth, checkInterval, checkInterval, timeUnit);
        logger.info("Started health checker for service instance: {}", serviceInstance.getInstanceId());
    }
    
    /**
     * Stop the health check
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Stopped health checker for service instance: {}", serviceInstance.getInstanceId());
    }
    
    /**
     * Perform the health check
     */
    private void checkHealth() {
        try {
            boolean isHealthy = healthChecker.check(serviceInstance);
            
            // Only report when health status changes
            if (isHealthy != lastHealthStatus) {
                logger.info("Health status changed for service instance {}: {} -> {}", 
                           serviceInstance.getInstanceId(), lastHealthStatus, isHealthy);
                healthReporter.accept(isHealthy);
                lastHealthStatus = isHealthy;
            }
        } catch (Exception e) {
            logger.error("Error during health check for service instance: " + serviceInstance.getInstanceId(), e);
            // If the check fails, consider the service unhealthy
            if (lastHealthStatus) {
                healthReporter.accept(false);
                lastHealthStatus = false;
            }
        }
    }
    
    /**
     * Get the health status from the last check
     */
    public boolean getLastHealthStatus() {
        return lastHealthStatus;
    }
    
    /**
     * Check if the health checker is running
     */
    public boolean isRunning() {
        return running;
    }
}