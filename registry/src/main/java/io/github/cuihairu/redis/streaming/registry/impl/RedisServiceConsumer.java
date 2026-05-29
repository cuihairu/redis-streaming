package io.github.cuihairu.redis.streaming.registry.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.registry.*;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.health.*;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import io.github.cuihairu.redis.streaming.registry.lua.RegistryLuaScriptExecutor;
import lombok.Getter;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Redis-based service consumer implementation
 * Supports health checking and status reporting (enabled via configuration)
 */
public class RedisServiceConsumer implements ServiceDiscovery, ServiceConsumer {

    private static final Logger logger = LoggerFactory.getLogger(RedisServiceConsumer.class);

    private final RedissonClient redissonClient;
    /**
     * -- GETTER --
     *  Get configuration
     *
     */
    @Getter
    private final ServiceConsumerConfig config;
    private volatile boolean running = false;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RegistryLuaScriptExecutor luaExecutor;

    // Listener management
    private final Map<String, Set<ServiceChangeListener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, RTopic> subscriptions = new ConcurrentHashMap<>();

    /**
     * -- GETTER --
     *  Get health check manager
     */
    @Getter
    private HealthCheckManager healthCheckManager;
    private final Map<String, ServiceInstance> discoveredInstances = new ConcurrentHashMap<>();

    public RedisServiceConsumer(RedissonClient redissonClient) {
        this(redissonClient, new ServiceConsumerConfig());
    }

    public RedisServiceConsumer(RedissonClient redissonClient, ServiceConsumerConfig config) {
        this.redissonClient = redissonClient;
        this.config = config != null ? config : new ServiceConsumerConfig();
        this.luaExecutor = new RegistryLuaScriptExecutor(redissonClient);
        initializeHealthCheckManager();
    }

    /**
     * Initialize health check manager (if configured to enable)
     */
    private void initializeHealthCheckManager() {
        if (config.isEnableHealthCheck()) {
            // Create standard health checker
            StandardHealthChecker standardHealthChecker = new StandardHealthChecker();

            // Register protocol-specific health checkers (using configured timeout)
            int timeout = config.getHealthCheckTimeout();
            HttpHealthChecker httpHealthChecker = new HttpHealthChecker(timeout, timeout, "/health");
            TcpHealthChecker tcpHealthChecker = new TcpHealthChecker(timeout);
            WebSocketHealthChecker webSocketHealthChecker = new WebSocketHealthChecker(timeout);

            // Create health check manager
            this.healthCheckManager = new HealthCheckManager(
                standardHealthChecker,
                this::reportHealthStatus,
                config.getHealthCheckInterval(),
                config.getHealthCheckTimeUnit()
            );

            // Register protocol health checkers
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.HTTP, httpHealthChecker);
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.HTTPS, httpHealthChecker);
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.TCP, tcpHealthChecker);
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.WS, webSocketHealthChecker);
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.WSS, webSocketHealthChecker);

            logger.info("Health check manager initialized with {} second interval", config.getHealthCheckInterval());
        }
    }

    /**
     * Report health status changes
     */
    private void reportHealthStatus(String instanceId, boolean isHealthy) {
        ServiceInstance instance = discoveredInstances.get(instanceId);
        if (instance != null) {
            logger.info("Service instance {} health status changed to: {}", instanceId, isHealthy);

            // Update instance health status
            if (instance instanceof DefaultServiceInstance) {
                ServiceInstance updatedInstance = DefaultServiceInstance.builder()
                    .serviceName(instance.getServiceName())
                    .instanceId(instance.getInstanceId())
                    .host(instance.getHost())
                    .port(instance.getPort())
                    .protocol(instance.getProtocol())
                    .enabled(instance.isEnabled())
                    .healthy(isHealthy)  // Update health status
                    .weight(instance.getWeight())
                    .metadata(instance.getMetadata())
                    .build();

                discoveredInstances.put(instanceId, updatedInstance);
            }

            // Trigger service change event
            notifyHealthStatusChange(instance.getServiceName(), instanceId, isHealthy);
        }
    }

    /**
     * Notify health status changes
     */
    private void notifyHealthStatusChange(String serviceName, String instanceId, boolean isHealthy) {
        Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners != null && !serviceListeners.isEmpty()) {
            ServiceChangeAction action = isHealthy ?
                ServiceChangeAction.HEALTH_RECOVERY : ServiceChangeAction.HEALTH_FAILURE;
            ServiceInstance instance = discoveredInstances.get(instanceId);

            if (instance != null) {
                // Get all current instances
                List<ServiceInstance> allInstances = discover(serviceName);

                for (ServiceChangeListener listener : serviceListeners) {
                    try {
                        listener.onServiceChange(serviceName, action, instance, allInstances);
                    } catch (Exception e) {
                        logger.error("Error notifying health status change to listener", e);
                    }
                }
            }
        }
    }

    @Override
    public List<ServiceInstance> discover(String serviceName) {
        if (!running) {
            throw new IllegalStateException("ServiceDiscovery is not running");
        }

        try {
            // Use three-level storage structure to get service instances
            String heartbeatsKey = config.getHeartbeatKey(serviceName);
            RScoredSortedSet<String> heartbeatsSet = redissonClient.getScoredSortedSet(heartbeatsKey, StringCodec.INSTANCE);

            // Get all non-expired instance IDs
            long currentTime = System.currentTimeMillis();
            long heartbeatTimeoutMs = config.getHeartbeatTimeoutSeconds() * 1000L;
            long expiredTime = currentTime - heartbeatTimeoutMs;
            Collection<String> activeInstanceIds = heartbeatsSet.valueRange(expiredTime, false, Double.MAX_VALUE, false);

            List<ServiceInstance> instances = new ArrayList<>();

            for (String instanceId : activeInstanceIds) {
                try {
                    // Get instance details from Hash structure (using StringCodec to match Lua scripts)
                    String instanceKey = config.getServiceInstanceKey(serviceName, instanceId);
                    RMap<String, String> instanceMap = redissonClient.getMap(instanceKey, StringCodec.INSTANCE);
                    Map<String, String> instanceData = instanceMap.readAllMap();

                    if (!instanceData.isEmpty()) {
                        ServiceInstance instance = buildServiceInstance(serviceName, instanceId, instanceData);
                        if (instance != null) {
                            instances.add(instance);

                            // Cache discovered instances
                            discoveredInstances.put(instanceId, instance);

                            // If health checking is enabled, register health checker
                            if (config.isEnableHealthCheck() && healthCheckManager != null) {
                                healthCheckManager.registerServiceInstance(instance);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load instance {} for service {}", instanceId, serviceName, e);
                }
            }

            logger.debug("Discovered {} instances for service: {}", instances.size(), serviceName);
            return instances;

        } catch (Exception e) {
            logger.error("Failed to discover instances for service: {}", serviceName, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<ServiceInstance> getAllInstances(String serviceName) {
        return discover(serviceName);
    }

    @Override
    public List<ServiceInstance> getHealthyInstances(String serviceName) {
        return discoverHealthy(serviceName);
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceName, boolean healthy) {
        if (healthy) {
            return discoverHealthy(serviceName);
        } else {
            return discover(serviceName);
        }
    }

    @Override
    public List<ServiceInstance> discoverHealthy(String serviceName) {
        List<ServiceInstance> allInstances = discover(serviceName);

        // Filter healthy and enabled instances
        return allInstances.stream()
                .filter(instance -> instance.isEnabled() && instance.isHealthy())
                .filter(this::isInstanceHeartbeatValid)
                .collect(Collectors.toList());
    }

    @Override
    public void subscribe(String serviceName, ServiceChangeListener listener) {
        if (!running) {
            throw new IllegalStateException("ServiceDiscovery is not running");
        }

        // Add listener
        listeners.computeIfAbsent(serviceName, k -> ConcurrentHashMap.newKeySet()).add(listener);

        // If this is the first listener, create Redis subscription
        if (!subscriptions.containsKey(serviceName)) {
            RTopic topic = redissonClient.getTopic(
                config.getServiceChangeChannelKey(serviceName), new org.redisson.codec.JsonJacksonCodec());

            topic.addListener(io.github.cuihairu.redis.streaming.registry.event.ServiceChangeEvent.class, (channel, message) -> {
                handleServiceChangeEvent(serviceName, message);
            });

            subscriptions.put(serviceName, topic);
            logger.info("Subscribed to service changes for: {}", serviceName);
        }

        // Immediately notify current service instance list
        try {
            List<ServiceInstance> currentInstances = discoverHealthy(serviceName);
            if (!currentInstances.isEmpty()) {
                // Notify each instance as a "current" event
                for (ServiceInstance instance : currentInstances) {
                    listener.onServiceChange(serviceName, ServiceChangeAction.CURRENT, instance, currentInstances);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to notify current instances for service: {}", serviceName, e);
        }
    }

    @Override
    public void unsubscribe(String serviceName, ServiceChangeListener listener) {
        Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners != null) {
            serviceListeners.remove(listener);

            // If no listeners left, cancel Redis subscription
            if (serviceListeners.isEmpty()) {
                listeners.remove(serviceName);

                RTopic topic = subscriptions.remove(serviceName);
                if (topic != null) {
                    topic.removeAllListeners();
                    logger.info("Unsubscribed from service changes for: {}", serviceName);
                }

                // Remove health checking
                if (config.isEnableHealthCheck() && healthCheckManager != null) {
                    // Remove health checkers for all instances of this service
                    discoveredInstances.entrySet().removeIf(entry -> {
                        ServiceInstance instance = entry.getValue();
                        if (serviceName.equals(instance.getServiceName())) {
                            healthCheckManager.unregisterServiceInstance(instance.getInstanceId());
                            return true;
                        }
                        return false;
                    });
                }
            }
        }
    }

    @Override
    public void start() {
        if (running) {
            return;
        }

        running = true;

        // Start health check manager
        if (config.isEnableHealthCheck() && healthCheckManager != null) {
            healthCheckManager.startAll();
        }

        logger.info("RedisServiceConsumer started with health check: {}", config.isEnableHealthCheck());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        // Stop health checking
        if (healthCheckManager != null) {
            healthCheckManager.stopAll();
        }

        // Clean up all subscriptions
        for (Map.Entry<String, RTopic> entry : subscriptions.entrySet()) {
            try {
                entry.getValue().removeAllListeners();
            } catch (Exception e) {
                logger.warn("Failed to cleanup subscription for service: {}", entry.getKey(), e);
            }
        }

        subscriptions.clear();
        listeners.clear();
        discoveredInstances.clear();

        logger.info("RedisServiceConsumer stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Handle service change events
     */
    private void handleServiceChangeEvent(String serviceName, io.github.cuihairu.redis.streaming.registry.event.ServiceChangeEvent message) {
        try {
            String instanceId = message.getInstanceId();

            Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
            if (serviceListeners == null || serviceListeners.isEmpty()) {
                return;
            }

            // Check if discovery is still running to avoid IllegalStateException
            if (!running) {
                logger.debug("Ignoring service change event for {} - discovery is stopped", serviceName);
                return;
            }

            // Parse action
            ServiceChangeAction action = message.getAction();

            // Get all current healthy instance list
            List<ServiceInstance> allInstances = discoverHealthy(serviceName);

            // Build changed instance
            ServiceInstance changedInstance = null;
            if (action == ServiceChangeAction.REMOVED) {
                // For delete events, build instance info from message snapshot
                var snap = message.getInstance();
                if (snap != null) {
                    Map<String,String> data = new java.util.HashMap<>();
                    data.put("host", snap.getHost());
                    data.put("port", String.valueOf(snap.getPort()));
                    data.put("protocol", snap.getProtocol());
                    data.put("enabled", String.valueOf(snap.isEnabled()));
                    data.put("healthy", String.valueOf(snap.isHealthy()));
                    data.put("weight", String.valueOf(snap.getWeight()));
                    if (snap.getMetadata() != null) {
                        // serialize to JSON string for buildServiceInstance which expects metadata as map later
                        // but buildServiceInstance already expects map fields in string map; we'll populate after
                    }
                    changedInstance = buildServiceInstance(serviceName, instanceId, data);
                    // inject metadata after building (since build uses protocol/host/port first)
                    if (changedInstance instanceof DefaultServiceInstance && snap.getMetadata() != null) {
                        ((DefaultServiceInstance) changedInstance).setMetadata(new java.util.HashMap<>(snap.getMetadata()));
                    }
                }
            } else {
                // For add and update events, find from current instance list
                changedInstance = allInstances.stream()
                    .filter(instance -> instanceId.equals(instance.getInstanceId()))
                    .findFirst()
                    .orElse(null);
            }

            // Notify all listeners
            for (ServiceChangeListener listener : serviceListeners) {
                try {
                    listener.onServiceChange(serviceName, action, changedInstance, allInstances);
                } catch (Exception e) {
                    logger.error("Error in service change listener", e);
                }
            }

            logger.debug("Processed service change event: {} {} {}", serviceName, action, instanceId);

        } catch (Exception e) {
            logger.error("Failed to handle service change event for: {}", serviceName, e);
        }
    }

    /**
     * Build service instance object
     */
    private ServiceInstance buildServiceInstance(String serviceName, String instanceId, Map<String, String> data) {
        ServiceInstance si = InstanceEntryCodec.parseInstance(serviceName, instanceId, data);
        if (si == null) {
            logger.warn("Invalid instance data for {}:{}", serviceName, instanceId);
        }
        return si;
    }

    /**
     * Parse protocol
     */
    private Protocol parseProtocol(String protocolName) {
        if (protocolName == null) {
            return StandardProtocol.HTTP;
        }
        try {
            // Try to parse as standard protocol (consistent with write side)
            return StandardProtocol.fromName(protocolName);
        } catch (IllegalArgumentException e) {
            // Fallback to HTTP to avoid discovery failure due to unknown protocol
            return StandardProtocol.HTTP;
        }
    }

    /**
     * Check if instance heartbeat is valid
     */
    private boolean isInstanceHeartbeatValid(ServiceInstance instance) {
        try {
            String serviceName = instance.getServiceName();
            String instanceId = instance.getInstanceId();

            RScoredSortedSet<String> heartbeatSet = redissonClient.getScoredSortedSet(
                config.getHeartbeatKey(serviceName), StringCodec.INSTANCE);

            Double lastHeartbeatScore = heartbeatSet.getScore(instanceId);
            if (lastHeartbeatScore == null) {
                return false;
            }

            long lastHeartbeatTime = lastHeartbeatScore.longValue();
            long currentTime = System.currentTimeMillis();
            long heartbeatTimeoutMs = config.getHeartbeatTimeoutSeconds() * 1000L;

            return (currentTime - lastHeartbeatTime) <= heartbeatTimeoutMs;

        } catch (Exception e) {
            logger.warn("Failed to check heartbeat for instance {}:{}",
                instance.getServiceName(), instance.getInstanceId(), e);
            return false;
        }
    }

    /**
     * List all service names
     */
    public List<String> listServices() {
        if (!running) {
            throw new IllegalStateException("ServiceDiscovery is not running");
        }

        try {
            String servicesKey = config.getRegistryKeys().getServicesIndexKey();
            RSet<String> servicesSet = redissonClient.getSet(servicesKey, StringCodec.INSTANCE);
            return new ArrayList<>(servicesSet.readAll());
        } catch (Exception e) {
            logger.error("Failed to list services", e);
            return Collections.emptyList();
        }
    }

    /**
     * Manually trigger service discovery
     */
    public void refreshServiceInstances(String serviceName) {
        if (running) {
            discover(serviceName);
        }
    }

    /**
     * Get instance health status
     */
    public boolean isInstanceHealthy(String instanceId) {
        if (healthCheckManager != null) {
            return healthCheckManager.isInstanceHealthy(instanceId);
        }

        // If health checking is not enabled, get from cached instances
        ServiceInstance instance = discoveredInstances.get(instanceId);
        return instance != null && instance.isHealthy();
    }

    /**
     * Get discovered service instance count
     */
    public int getDiscoveredInstanceCount() {
        return discoveredInstances.size();
    }

    /**
     * Get health checker count
     */
    public int getActiveHealthCheckersCount() {
        return healthCheckManager != null ? healthCheckManager.getHealthCheckerCount() : 0;
    }

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
     *
     * // Caution: string magnitude comparison (lexicographic order)
     * filters.put("zone:>", "zone-a");     // lexicographic comparison
     *
     * // Not supported: version number comparison (use exact match or version tags)
     * // filters.put("version:>=", "1.10.0");  // Wrong!
     * filters.put("version", "1.10.0");    // Correct: exact match
     * </pre>
     *
     * @return list of matching service instances
     */
    @Override
    public List<ServiceInstance> discoverByMetadata(String serviceName, Map<String, String> metadataFilters) {
        if (!running) {
            throw new IllegalStateException("ServiceDiscovery is not running");
        }

        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return discover(serviceName);
        }

        try {
            // Convert metadata filter conditions to JSON
            String metadataFiltersJson = objectMapper.writeValueAsString(metadataFilters);

            // Use Lua script for filtering
            String heartbeatsKey = config.getHeartbeatKey(serviceName);
            long currentTime = System.currentTimeMillis();
            long heartbeatTimeoutMs = config.getHeartbeatTimeoutSeconds() * 1000L;

            // Use new interface (metadata-only), pass null for metricsFilters
            List<String> matchedInstanceIds = luaExecutor.executeGetInstancesByFilters(
                    heartbeatsKey,
                    config.getRegistryKeys().getKeyPrefix(),
                    serviceName,
                    currentTime,
                    heartbeatTimeoutMs,
                    metadataFiltersJson,
                    null
            );

            // Load complete instance information by instance ID
            List<ServiceInstance> instances = new ArrayList<>();
            for (String instanceId : matchedInstanceIds) {
                try {
                    String instanceKey = config.getServiceInstanceKey(serviceName, instanceId);
                    RMap<String, String> instanceMap = redissonClient.getMap(instanceKey, StringCodec.INSTANCE);
                    Map<String, String> instanceData = instanceMap.readAllMap();

                    if (!instanceData.isEmpty()) {
                        ServiceInstance instance = buildServiceInstance(serviceName, instanceId, instanceData);
                        if (instance != null) {
                            instances.add(instance);

                            // Cache discovered instances
                            discoveredInstances.put(instanceId, instance);

                            // If health checking is enabled, register health checker
                            if (config.isEnableHealthCheck() && healthCheckManager != null) {
                                healthCheckManager.registerServiceInstance(instance);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load instance {} for service {}", instanceId, serviceName, e);
                }
            }

            logger.debug("Discovered {} instances for service: {} with metadata filters: {}",
                    instances.size(), serviceName, metadataFilters);
            return instances;

        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize metadata filters to JSON", e);
            throw new RuntimeException("Metadata filter serialization failed", e);
        } catch (Exception e) {
            logger.error("Failed to discover instances by metadata for service: {}", serviceName, e);
            return Collections.emptyList();
        }
    }

    /**
     * Get healthy service instances filtered by metadata
     *
     * @param serviceName service name
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching and healthy service instances
     */
    @Override
    public List<ServiceInstance> discoverHealthyByMetadata(String serviceName, Map<String, String> metadataFilters) {
        List<ServiceInstance> allMatched = discoverByMetadata(serviceName, metadataFilters);

        // Filter healthy and enabled instances
        return allMatched.stream()
                .filter(instance -> instance.isEnabled() && instance.isHealthy())
                .filter(this::isInstanceHeartbeatValid)
                .collect(Collectors.toList());
    }

    // ==================== Extension: support metrics filtering ====================

    /**
     * Use combined metadata and metrics for server-side filtering
     */
    public List<ServiceInstance> discoverByFilters(String serviceName,
                                                   Map<String, String> metadataFilters,
                                                   Map<String, String> metricsFilters) {
        if (!running) {
            throw new IllegalStateException("ServiceDiscovery is not running");
        }

        try {
            String metadataJson = metadataFilters != null ? objectMapper.writeValueAsString(metadataFilters) : null;
            String metricsJson = metricsFilters != null ? objectMapper.writeValueAsString(metricsFilters) : null;

            String heartbeatsKey = config.getHeartbeatKey(serviceName);
            long currentTime = System.currentTimeMillis();
            long heartbeatTimeoutMs = config.getHeartbeatTimeoutSeconds() * 1000L;

            List<String> matchedInstanceIds = luaExecutor.executeGetInstancesByFilters(
                    heartbeatsKey,
                    config.getRegistryKeys().getKeyPrefix(),
                    serviceName,
                    currentTime,
                    heartbeatTimeoutMs,
                    metadataJson,
                    metricsJson
            );

            List<ServiceInstance> instances = new ArrayList<>();
            for (String instanceId : matchedInstanceIds) {
                try {
                    String instanceKey = config.getServiceInstanceKey(serviceName, instanceId);
                    RMap<String, String> instanceMap = redissonClient.getMap(instanceKey, StringCodec.INSTANCE);
                    Map<String, String> instanceData = instanceMap.readAllMap();
                    if (!instanceData.isEmpty()) {
                        ServiceInstance instance = buildServiceInstance(serviceName, instanceId, instanceData);
                        if (instance != null) {
                            instances.add(instance);
                            discoveredInstances.put(instanceId, instance);
                            if (config.isEnableHealthCheck() && healthCheckManager != null) {
                                healthCheckManager.registerServiceInstance(instance);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load instance {} for service {}", instanceId, serviceName, e);
                }
            }

            return instances;

        } catch (Exception e) {
            logger.error("Failed to discover instances by filters for service: {}", serviceName, e);
            return Collections.emptyList();
        }
    }

    public List<ServiceInstance> discoverHealthyByFilters(String serviceName,
                                                          Map<String, String> metadataFilters,
                                                          Map<String, String> metricsFilters) {
        List<ServiceInstance> all = discoverByFilters(serviceName, metadataFilters, metricsFilters);
        return all.stream()
                .filter(instance -> instance.isEnabled() && instance.isHealthy())
                .filter(this::isInstanceHeartbeatValid)
                .collect(Collectors.toList());
    }
    // ==================== ServiceConsumer interface implementation (delegate methods) ====================

    /**
     * Get instances filtered by metadata (supports comparison operators)
     * This is an alias for discoverByMetadata
     *
     * @param serviceName the name of the service to discover
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching service instances
     */
    @Override
    public List<ServiceInstance> getInstancesByMetadata(String serviceName, Map<String, String> metadataFilters) {
        return discoverByMetadata(serviceName, metadataFilters);
    }

    /**
     * Get healthy instances filtered by metadata
     * This is an alias for discoverHealthyByMetadata
     *
     * @param serviceName the name of the service to discover
     * @param metadataFilters metadata filter conditions (AND relationship)
     * @return list of matching and healthy service instances
     */
    @Override
    public List<ServiceInstance> getHealthyInstancesByMetadata(String serviceName, Map<String, String> metadataFilters) {
        return discoverHealthyByMetadata(serviceName, metadataFilters);
    }
}
