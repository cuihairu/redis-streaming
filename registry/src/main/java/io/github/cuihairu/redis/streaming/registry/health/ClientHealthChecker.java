package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Client health checker
 * Responsible for probing the health status of service instances and reporting to the registry
 *
 * <p>B-08: the first check runs on the checker's executor instead of the calling thread,
 * the scheduling thread is a daemon, and instances can share one scheduler so thread
 * count stays independent of the instance count.
 */
public class ClientHealthChecker {
    private static final Logger logger = LoggerFactory.getLogger(ClientHealthChecker.class);

    private final ServiceInstance serviceInstance;
    private final HealthChecker healthChecker;
    private final Consumer<Boolean> healthReporter;
    /** Executor the checks run on: a shared pool (B-08) or this checker's own. */
    private final ScheduledExecutorService executor;
    /** Non-null only when this checker owns its scheduler and must shut it down in stop(). */
    private final ScheduledExecutorService ownedScheduler;
    private final long checkInterval;
    private final TimeUnit timeUnit;

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile boolean lastHealthStatus = true;
    private volatile boolean running = false;

    public ClientHealthChecker(ServiceInstance serviceInstance,
                              HealthChecker healthChecker,
                              Consumer<Boolean> healthReporter,
                              long checkInterval,
                              TimeUnit timeUnit) {
        this(serviceInstance, healthChecker, healthReporter, checkInterval, timeUnit, null);
    }

    /**
     * Share one scheduler across checkers (B-08) instead of one thread per instance.
     * A {@code null} shared executor keeps the self-managed single-thread scheduler
     * of the public constructor.
     */
    ClientHealthChecker(ServiceInstance serviceInstance,
                        HealthChecker healthChecker,
                        Consumer<Boolean> healthReporter,
                        long checkInterval,
                        TimeUnit timeUnit,
                        ScheduledExecutorService sharedExecutor) {
        this.serviceInstance = serviceInstance;
        this.healthChecker = healthChecker;
        this.healthReporter = healthReporter;
        this.checkInterval = checkInterval;
        this.timeUnit = timeUnit;
        if (sharedExecutor != null) {
            this.executor = sharedExecutor;
            this.ownedScheduler = null;
        } else {
            this.ownedScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "health-checker-" + serviceInstance.getInstanceId());
                    t.setDaemon(true);
                    return t;
                });
            this.executor = ownedScheduler;
        }
    }

    /**
     * Start the health check
     */
    public void start() {
        if (running) {
            return;
        }

        running = true;
        // delay 0: the first check runs on the executor, so start() — called per instance
        // from discover/subscribe — never blocks on a connect+read timeout (B-08)
        scheduledTask = executor.scheduleWithFixedDelay(this::checkHealth, 0, checkInterval, timeUnit);
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
        ScheduledFuture<?> task = scheduledTask;
        if (task != null) {
            // let an in-flight probe finish without interrupting it
            task.cancel(false);
        }
        if (ownedScheduler != null) {
            ownedScheduler.shutdown();
            try {
                if (!ownedScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    ownedScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                ownedScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
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
        } catch (Throwable e) {
            // Throwable, not Exception: an uncaught Error would silently kill the
            // fixed-delay schedule (no further checks, no log)
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