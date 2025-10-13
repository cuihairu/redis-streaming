package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.NamingServiceConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于 Redis 的命名服务实现
 * 整合服务注册和服务发现功能，提供统一的 NamingService 接口
 *
 * This implementation clearly separates service provider and consumer responsibilities
 * while maintaining backward compatibility with the legacy API.
 *
 * Implements multiple interfaces to support both perspectives:
 * - Business role: ServiceProvider, ServiceConsumer
 * - Technical operation: ServiceRegistry, ServiceDiscovery
 */
public class RedisNamingService implements NamingService, ServiceDiscovery, ServiceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RedisNamingService.class);

    private final RedissonClient redissonClient;
    private final RedisServiceProvider serviceProvider;
    private final RedisServiceConsumer serviceConsumer;
    private final NamingServiceConfig config;
    private volatile boolean running = false;

    public RedisNamingService(RedissonClient redissonClient) {
        this(redissonClient, new NamingServiceConfig());
    }

    public RedisNamingService(RedissonClient redissonClient, NamingServiceConfig config) {
        this.redissonClient = redissonClient;
        this.config = config != null ? config : new NamingServiceConfig();
        
        // Create role-specific configs from the unified config
        ServiceProviderConfig providerConfig = new ServiceProviderConfig();
        providerConfig.setKeyPrefix(this.config.getKeyPrefix());
        providerConfig.setEnableKeyPrefix(this.config.isEnableKeyPrefix());
        
        ServiceConsumerConfig consumerConfig = new ServiceConsumerConfig();
        consumerConfig.setKeyPrefix(this.config.getKeyPrefix());
        consumerConfig.setEnableKeyPrefix(this.config.isEnableKeyPrefix());
        
        this.serviceProvider = new RedisServiceProvider(redissonClient, providerConfig);
        this.serviceConsumer = new RedisServiceConsumer(redissonClient, consumerConfig);
    }

    // ==================== ServiceProvider 接口实现 ====================

    @Override
    public void register(ServiceInstance instance) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }

        serviceProvider.register(instance);
        logger.info("Registered instance for service: {}: {}", instance.getServiceName(), instance.getInstanceId());
    }

    @Override
    public void deregister(ServiceInstance instance) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }

        serviceProvider.deregister(instance);
        logger.info("Deregistered instance for service: {}: {}", instance.getServiceName(), instance.getInstanceId());
    }

    @Override
    public void sendHeartbeat(ServiceInstance instance) {
        if (!running) {
            return; // 心跳可以静默失败
        }

        serviceProvider.sendHeartbeat(instance);
        logger.debug("Sent heartbeat for service: {}: {}", instance.getServiceName(), instance.getInstanceId());
    }

    @Override
    public void batchSendHeartbeats(List<ServiceInstance> instances) {
        if (!running || instances.isEmpty()) {
            return;
        }

        serviceProvider.batchSendHeartbeats(instances);
        logger.debug("Sent batch heartbeat for {} instances of service: {}", 
            instances.size(), instances.get(0).getServiceName());
    }

    // ==================== ServiceConsumer 接口实现 ====================

    @Override
    public List<ServiceInstance> getAllInstances(String serviceName) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }

        List<ServiceInstance> instances = serviceConsumer.discover(serviceName);
        logger.debug("Retrieved {} instances for service: {}", instances.size(), serviceName);
        return instances;
    }

    @Override
    public List<ServiceInstance> discover(String serviceName) {
        return getAllInstances(serviceName);
    }

    @Override
    public List<ServiceInstance> getHealthyInstances(String serviceName) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }

        List<ServiceInstance> instances = serviceConsumer.discoverHealthy(serviceName);
        logger.debug("Retrieved {} healthy instances for service: {}", instances.size(), serviceName);
        return instances;
    }

    @Override
    public List<ServiceInstance> discoverHealthy(String serviceName) {
        return getHealthyInstances(serviceName);
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName, boolean healthy) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }

        List<ServiceInstance> instances;
        if (healthy) {
            instances = serviceConsumer.discoverHealthy(serviceName);
            logger.debug("Selected {} healthy instances for service: {}", instances.size(), serviceName);
        } else {
            instances = serviceConsumer.discover(serviceName);
            logger.debug("Selected {} instances for service: {}", instances.size(), serviceName);
        }

        return instances;
    }

    @Override
    public void subscribe(String serviceName, ServiceChangeListener listener) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }

        serviceConsumer.subscribe(serviceName, listener);
        logger.info("Subscribed to service changes for: {}", serviceName);
    }

    @Override
    public void unsubscribe(String serviceName, ServiceChangeListener listener) {
        if (!running) {
            logger.warn("Attempting to unsubscribe when NamingService is not running: {}", serviceName);
            return;
        }

        serviceConsumer.unsubscribe(serviceName, listener);
        logger.info("Unsubscribed from service changes for: {}", serviceName);
    }

    // ==================== Metadata 过滤查询 ====================

    /**
     * 根据 metadata 过滤获取服务实例（支持比较运算符）
     *
     * @param serviceName 服务名称
     * @param metadataFilters metadata过滤条件（AND关系），支持比较运算符：
     * <ul>
     *   <li>"field": "value" - 等于（默认）</li>
     *   <li>"field:==": "value" - 等于（显式）</li>
     *   <li>"field:!=": "value" - 不等于</li>
     *   <li>"field:>": "value" - 大于</li>
     *   <li>"field:>=": "value" - 大于等于</li>
     *   <li>"field:<": "value" - 小于</li>
     *   <li>"field:<=": "value" - 小于等于</li>
     * </ul>
     *
     * <p>比较规则：</p>
     * <ol>
     *   <li>优先尝试数值比较（推荐用于 weight, age, cpu 等）</li>
     *   <li>失败则使用字符串比较（字典序，谨慎使用）</li>
     * </ol>
     *
     * <p>示例：</p>
     * <pre>
     * // ✅ 推荐：数值比较
     * Map&lt;String, String&gt; filters = new HashMap&lt;&gt;();
     * filters.put("weight:>=", "10");      // 权重 >= 10
     * filters.put("cpu_usage:<", "80");    // CPU使用率 < 80
     *
     * // ✅ 推荐：字符串相等
     * filters.put("region", "us-east-1");  // 精确匹配
     * filters.put("status:!=", "down");    // 状态不等于
     * </pre>
     *
     * @return 匹配的服务实例列表
     */
    @Override
    public List<ServiceInstance> getInstancesByMetadata(String serviceName, java.util.Map<String, String> metadataFilters) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }

        List<ServiceInstance> instances = serviceConsumer.discoverByMetadata(serviceName, metadataFilters);
        logger.debug("Retrieved {} instances for service: {} with metadata filters: {}",
                instances.size(), serviceName, metadataFilters);
        return instances;
    }

    /**
     * 根据 metadata 过滤获取健康的服务实例
     *
     * @param serviceName 服务名称
     * @param metadataFilters metadata过滤条件（AND关系）
     * @return 匹配且健康的服务实例列表
     */
    @Override
    public List<ServiceInstance> getHealthyInstancesByMetadata(String serviceName, java.util.Map<String, String> metadataFilters) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }

        List<ServiceInstance> instances = serviceConsumer.discoverHealthyByMetadata(serviceName, metadataFilters);
        logger.debug("Retrieved {} healthy instances for service: {} with metadata filters: {}",
                instances.size(), serviceName, metadataFilters);
        return instances;
    }

    // ==================== ServiceDiscovery 接口实现 ====================

    @Override
    public List<ServiceInstance> discoverByMetadata(String serviceName, java.util.Map<String, String> metadataFilters) {
        return getInstancesByMetadata(serviceName, metadataFilters);
    }

    @Override
    public List<ServiceInstance> discoverHealthyByMetadata(String serviceName, java.util.Map<String, String> metadataFilters) {
        return getHealthyInstancesByMetadata(serviceName, metadataFilters);
    }

    // ==================== ServiceRegistry 接口实现 ====================

    /**
     * Alias for sendHeartbeat (ServiceRegistry interface)
     *
     * @param instance the service instance to send heartbeat for
     */
    @Override
    public void heartbeat(ServiceInstance instance) {
        sendHeartbeat(instance);
    }

    /**
     * Alias for batchSendHeartbeats (ServiceRegistry interface)
     *
     * @param instances the list of service instances to send heartbeats for
     */
    @Override
    public void batchHeartbeat(List<ServiceInstance> instances) {
        batchSendHeartbeats(instances);
    }

    // ==================== 生命周期管理 ====================

    @Override
    public void start() {
        if (running) {
            logger.warn("NamingService is already running");
            return;
        }

        serviceProvider.start();
        serviceConsumer.start();
        running = true;

        logger.info("RedisNamingService started successfully");
    }

    @Override
    public void stop() {
        if (!running) {
            logger.warn("NamingService is not running");
            return;
        }

        running = false;
        serviceProvider.stop();
        serviceConsumer.stop();

        logger.info("RedisNamingService stopped successfully");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
    
    /**
     * 获取配置
     * 
     * @return Redis注册中心配置
     */
    public NamingServiceConfig getConfig() {
        return config;
    }
}