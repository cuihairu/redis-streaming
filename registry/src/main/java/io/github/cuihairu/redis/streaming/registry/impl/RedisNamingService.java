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
 * Redis-based naming service implementation
 * Integrates service registration and discovery, provides unified NamingService interface
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

    // ==================== ServiceProvider interface implementation ====================

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
            return; // Heartbeat can fail silently
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

    // ==================== ServiceConsumer interface implementation ====================

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

    // ==================== Metadata filter queries ====================

    /**
     * Get service instances filtered by metadata (supports comparison operators)
     *
     * @param serviceName service name
     * @param metadataFilters metadata filter conditions (AND relationship), supports comparison operators:
     * <ul>
     *   <li>"field": "value" - equals (default)</li>
     *   <li>"field:==": "value" - equals (explicit)</li>
     *   <li>"field:!=": "value" - not equals</li>
     *   <li>"field:>": "value" - greater than</li>
     *   <li>"field:>=": "value" - greater than or equals</li>
     *   <li>"field:&lt;": "value" - less than</li>
     *   <li>"field:&lt;=": "value" - less than or equals</li>
     * </ul>
     *
     * <p>Comparison rules:</p>
     * <ol>
     *   <li>Numeric comparison is attempted first (recommended for weight, age, cpu, etc.)</li>
     *   <li>Falls back to string comparison (lexicographic order, use with caution)</li>
     * </ol>
     *
     * <p>Examples:</p>
     * <pre>
     * // Recommended: numeric comparison
     * Map&lt;String, String&gt; filters = new HashMap&lt;&gt;();
     * filters.put("weight:>=", "10");      // weight >= 10
     * filters.put("cpu_usage:&lt;", "80");    // CPU usage &lt; 80
     *
     * // Recommended: string equality
     * filters.put("region", "us-east-1");  // exact match
     * filters.put("status:!=", "down");    // status not equals
     * </pre>
     *
     * @return list of matching service instances
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
     * Get healthy service instances filtered by metadata
     *
     * @param serviceName service name
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching and healthy service instances
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

    // ==================== ServiceDiscovery interface implementation ====================

    @Override
    public List<ServiceInstance> discoverByMetadata(String serviceName, java.util.Map<String, String> metadataFilters) {
        return getInstancesByMetadata(serviceName, metadataFilters);
    }

    @Override
    public List<ServiceInstance> discoverHealthyByMetadata(String serviceName, java.util.Map<String, String> metadataFilters) {
        return getHealthyInstancesByMetadata(serviceName, metadataFilters);
    }

    // ==================== Extension: support metrics filtering ====================

    /**
     * Convenience methods to use combined metadata/metrics filters
     */
    public java.util.List<ServiceInstance> getInstancesByFilters(String serviceName,
                                                                 java.util.Map<String, String> metadataFilters,
                                                                 java.util.Map<String, String> metricsFilters) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }
        java.util.List<ServiceInstance> instances = serviceConsumer.discoverByFilters(serviceName, metadataFilters, metricsFilters);
        logger.debug("Retrieved {} instances for service: {} with filters (metadata={}, metrics={})",
                instances.size(), serviceName, metadataFilters, metricsFilters);
        return instances;
    }

    public java.util.List<ServiceInstance> getHealthyInstancesByFilters(String serviceName,
                                                                        java.util.Map<String, String> metadataFilters,
                                                                        java.util.Map<String, String> metricsFilters) {
        if (!running) {
            throw new IllegalStateException("NamingService is not running");
        }
        java.util.List<ServiceInstance> instances = serviceConsumer.discoverHealthyByFilters(serviceName, metadataFilters, metricsFilters);
        logger.debug("Retrieved {} healthy instances for service: {} with filters (metadata={}, metrics={})",
                instances.size(), serviceName, metadataFilters, metricsFilters);
        return instances;
    }

    // ==================== Convenience: choose with load balancer ====================

    public ServiceInstance chooseHealthyInstance(String serviceName,
                                                 io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer lb,
                                                 java.util.Map<String, Object> context) {
        java.util.List<ServiceInstance> instances = getHealthyInstances(serviceName);
        return lb.choose(serviceName, instances, context);
    }

    public ServiceInstance chooseHealthyInstanceByFilters(String serviceName,
                                                          java.util.Map<String, String> metadataFilters,
                                                          java.util.Map<String, String> metricsFilters,
                                                          io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer lb,
                                                          java.util.Map<String, Object> context) {
        java.util.List<ServiceInstance> instances = getHealthyInstancesByFilters(serviceName, metadataFilters, metricsFilters);
        return lb.choose(serviceName, instances, context);
    }

    // ==================== ServiceRegistry interface implementation ====================

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

    // ==================== Lifecycle management ====================

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
     * Get configuration
     *
     * @return Redis registry configuration
     */
    public NamingServiceConfig getConfig() {
        return config;
    }
}
