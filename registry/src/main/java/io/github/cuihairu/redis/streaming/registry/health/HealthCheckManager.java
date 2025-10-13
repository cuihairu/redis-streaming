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
 * 健康检查管理器
 * 管理多个服务实例的健康检查器
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
     * 为指定协议注册健康检查器
     */
    public void registerProtocolHealthChecker(Protocol protocol, HealthChecker healthChecker) {
        protocolHealthCheckers.put(protocol, healthChecker);
        logger.info("Registered health checker for protocol: {}", protocol.getName());
    }
    
    /**
     * 获取指定协议的健康检查器
     */
    public HealthChecker getProtocolHealthChecker(Protocol protocol) {
        return protocolHealthCheckers.get(protocol);
    }
    
    /**
     * 为服务实例注册健康检查器
     */
    public void registerServiceInstance(ServiceInstance serviceInstance) {
        String uniqueId = serviceInstance.getUniqueId();

        if (healthCheckers.containsKey(uniqueId)) {
            logger.warn("Health checker already registered for instance: {}", uniqueId);
            return;
        }

        // 根据协议获取对应的健康检查器
        Protocol protocol =  serviceInstance.getProtocol();
        HealthChecker healthChecker = protocolHealthCheckers.get(protocol);
        if (healthChecker == null) {
            healthChecker = defaultHealthChecker;
        }

        // 如果默认的检查器都是 null,则直接返回
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
     * 移除服务实例的健康检查器
     * @param uniqueId 服务实例的全局唯一ID (serviceName:instanceId)
     */
    public void unregisterServiceInstance(String uniqueId) {
        ClientHealthChecker healthChecker = healthCheckers.remove(uniqueId);
        if (healthChecker != null) {
            healthChecker.stop();
            logger.info("Unregistered health checker for service instance: {}", uniqueId);
        }
    }
    
    /**
     * 移除服务实例的健康检查器
     */
    public void unregisterServiceInstance(ServiceInstance serviceInstance) {
        unregisterServiceInstance(serviceInstance.getUniqueId());
    }
    
    /**
     * 启动所有健康检查器
     */
    public void startAll() {
        healthCheckers.values().forEach(ClientHealthChecker::start);
        logger.info("Started all health checkers, count: {}", healthCheckers.size());
    }
    
    /**
     * 停止所有健康检查器
     */
    public void stopAll() {
        healthCheckers.values().forEach(ClientHealthChecker::stop);
        healthCheckers.clear();
        logger.info("Stopped all health checkers");
    }
    
    /**
     * 获取健康检查器数量
     */
    public int getHealthCheckerCount() {
        return healthCheckers.size();
    }
    
    /**
     * 检查指定实例的健康状态
     * @param uniqueId 服务实例的全局唯一ID (serviceName:instanceId)
     */
    public boolean isInstanceHealthy(String uniqueId) {
        ClientHealthChecker healthChecker = healthCheckers.get(uniqueId);
        return healthChecker != null && healthChecker.getLastHealthStatus();
    }
}