package io.github.cuihairu.redis.streaming.registry.health;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 客户端健康探测器
 * 负责探测服务实例的健康状态并汇报给注册中心
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
     * 启动健康检查
     */
    public void start() {
        if (running) {
            return;
        }
        
        running = true;
        scheduler.scheduleWithFixedDelay(this::checkHealth, 0, checkInterval, timeUnit);
        logger.info("Started health checker for service instance: {}", serviceInstance.getInstanceId());
    }
    
    /**
     * 停止健康检查
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
     * 执行健康检查
     */
    private void checkHealth() {
        try {
            boolean isHealthy = healthChecker.check(serviceInstance);
            
            // 只有在健康状态发生变化时才汇报
            if (isHealthy != lastHealthStatus) {
                logger.info("Health status changed for service instance {}: {} -> {}", 
                           serviceInstance.getInstanceId(), lastHealthStatus, isHealthy);
                healthReporter.accept(isHealthy);
                lastHealthStatus = isHealthy;
            }
        } catch (Exception e) {
            logger.error("Error during health check for service instance: " + serviceInstance.getInstanceId(), e);
            // 如果检查失败，认为服务不健康
            if (lastHealthStatus) {
                healthReporter.accept(false);
                lastHealthStatus = false;
            }
        }
    }
    
    /**
     * 获取上次检查的健康状态
     */
    public boolean getLastHealthStatus() {
        return lastHealthStatus;
    }
    
    /**
     * 检查健康检查器是否正在运行
     */
    public boolean isRunning() {
        return running;
    }
}