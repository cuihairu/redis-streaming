package io.github.cuihairu.redis.streaming.registry.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceProvider;
import io.github.cuihairu.redis.streaming.registry.ServiceRegistry;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatConfig;
import io.github.cuihairu.redis.streaming.registry.heartbeat.HeartbeatStateManager;
import io.github.cuihairu.redis.streaming.registry.heartbeat.UpdateDecision;
import io.github.cuihairu.redis.streaming.registry.keys.RegistryKeys;
import io.github.cuihairu.redis.streaming.registry.lua.RegistryLuaScriptExecutor;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsCollectionManager;
import io.github.cuihairu.redis.streaming.registry.metrics.MetricsConfig;
import io.github.cuihairu.redis.streaming.registry.metrics.CpuMetricCollector;
import io.github.cuihairu.redis.streaming.registry.metrics.MemoryMetricCollector;
import io.github.cuihairu.redis.streaming.registry.metrics.ApplicationMetricCollector;
import org.redisson.api.*;
import org.redisson.codec.JsonJacksonCodec;
import io.github.cuihairu.redis.streaming.registry.event.ServiceChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based service provider implementation
 * Supports three-level storage structure + smart heartbeat strategy (enabled via configuration)
 *
 * <p>Implements two interfaces:</p>
 * <ul>
 *   <li>{@link ServiceProvider} - Business role perspective</li>
 *   <li>{@link ServiceRegistry} - Technical operation perspective</li>
 * </ul>
 */
public class RedisServiceProvider implements ServiceProvider, ServiceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RedisServiceProvider.class);

    private final RedissonClient redissonClient;
    private final ServiceProviderConfig config;
    private final HeartbeatConfig heartbeatConfig;
    private final HeartbeatStateManager stateManager;
    private final MetricsCollectionManager metricsManager;
    private final RegistryLuaScriptExecutor luaExecutor;
    private final ObjectMapper objectMapper;
    private final RegistryKeys registryKeys;

    private volatile boolean running = false;
    private ScheduledExecutorService executorService;
    private ScheduledFuture<?> cleanupTask;

    public RedisServiceProvider(RedissonClient redissonClient) {
        this(redissonClient, new ServiceProviderConfig(), new HeartbeatConfig(), null);
    }

    public RedisServiceProvider(RedissonClient redissonClient, ServiceProviderConfig config) {
        this(redissonClient, config, new HeartbeatConfig(), null);
    }

    public RedisServiceProvider(RedissonClient redissonClient, ServiceProviderConfig config,
                                HeartbeatConfig heartbeatConfig, MetricsCollectionManager metricsManager) {
        this.redissonClient = redissonClient;
        this.config = config != null ? config : new ServiceProviderConfig();
        this.heartbeatConfig = heartbeatConfig != null ? heartbeatConfig : new HeartbeatConfig();
        this.stateManager = new HeartbeatStateManager(this.heartbeatConfig);
        // default metrics manager if none provided
        if (metricsManager == null) {
            java.util.List<io.github.cuihairu.redis.streaming.registry.metrics.MetricCollector> collectors = new java.util.ArrayList<>();
            collectors.add(new CpuMetricCollector());
            collectors.add(new MemoryMetricCollector());
            collectors.add(new ApplicationMetricCollector());
            // GC metrics (low cost)
            collectors.add(new io.github.cuihairu.redis.streaming.registry.metrics.GcMetricCollector());
            // disk collector (low cost)
            collectors.add(new io.github.cuihairu.redis.streaming.registry.metrics.DiskMetricCollector());
            // optional network collector if available (Linux /proc)
            try {
                Class<?> nc = Class.forName("io.github.cuihairu.redis.streaming.registry.metrics.NetworkMetricCollector");
                collectors.add((io.github.cuihairu.redis.streaming.registry.metrics.MetricCollector) nc.getDeclaredConstructor().newInstance());
            } catch (Throwable ignore) {}
            MetricsConfig mc = io.github.cuihairu.redis.streaming.registry.metrics.MetricsGlobal.getOrDefault();
            this.metricsManager = new MetricsCollectionManager(collectors, mc);
        } else {
            this.metricsManager = metricsManager;
        }
        this.luaExecutor = new RegistryLuaScriptExecutor(redissonClient);
        this.objectMapper = new ObjectMapper();
        this.registryKeys = this.config.getRegistryKeys();
    }

    @Override
    public void register(ServiceInstance instance) {
        if (!running) {
            throw new IllegalStateException("ServiceRegistry is not running");
        }

        try {
            String serviceName = instance.getServiceName();
            String instanceId = instance.getInstanceId();

            // Safely handle serviceName to prevent colons and other characters from causing key parsing issues
            String safeServiceName = RegistryKeys.validateAndSanitizeServiceName(serviceName);

            // Safely handle instanceId to prevent colons and other characters from causing key parsing issues
            String safeInstanceId = RegistryKeys.validateAndSanitizeInstanceId(instanceId);

            // If sanitized values changed, create a new instance object
            if (!serviceName.equals(safeServiceName) || !instanceId.equals(safeInstanceId)) {
                instance = createSafeInstance(instance, safeServiceName, safeInstanceId);
                logger.info("Instance sanitized: serviceName '{}' -> '{}', instanceId '{}' -> '{}'",
                    serviceName, safeServiceName, instanceId, safeInstanceId);
                // Update local variables
                serviceName = safeServiceName;
                instanceId = safeInstanceId;
            }

            long currentTime = System.currentTimeMillis();

            // Prepare instance data
            Map<String, Object> instanceData = InstanceEntryCodec.buildInstanceData(instance, currentTime);

            // Collect initial metrics
            if (metricsManager != null) {
                Map<String, Object> metrics = metricsManager.collectMetrics(true);
                instanceData.put("metrics", metrics);
            }

            // Use Lua script for atomic registration
            String servicesKey = registryKeys.getServicesIndexKey();
            String heartbeatKey = registryKeys.getServiceHeartbeatsKey(serviceName);
            String instanceKey = registryKeys.getServiceInstanceKey(serviceName, instanceId);

            // Determine if instance already existed to decide event type (ADDED or UPDATED)
            boolean existed = false;
            try {
                RMap<String, String> imap = redissonClient.getMap(instanceKey);
                existed = imap.isExists();
            } catch (Exception ignore) {
                // Non-critical path, fallback to ADDED on exception
            }

            luaExecutor.executeRegisterInstance(
                    servicesKey, heartbeatKey, instanceKey,
                    serviceName, instanceId, currentTime,
                    objectMapper.writeValueAsString(instanceData), config.getHeartbeatTimeoutSeconds()
            );

            // Notify service change (ADDED for first time, UPDATED for re-registration)
            notifyServiceChange(serviceName, existed ? ServiceChangeAction.UPDATED : ServiceChangeAction.ADDED, instance);

            // Initialize metadata hash in heartbeat state manager to ensure subsequent change detection works properly
            if (stateManager != null) {
                stateManager.markMetadataUpdateCompleted(safeServiceName, safeInstanceId,
                    new HashMap<>(instance.getMetadata()));
            }

            if (existed) {
                logger.info("Service instance re-registered (updated): {}:{}", serviceName, instanceId);
            } else {
                logger.info("Service instance registered: {}:{}", serviceName, instanceId);
            }

        } catch (Exception e) {
            logger.error("Failed to register service instance: {}:{}",
                    instance.getServiceName(), instance.getInstanceId(), e);
            throw new RuntimeException("Service registration failed", e);
        }
    }

    @Override
    public void deregister(ServiceInstance instance) {
        if (!running) {
            throw new IllegalStateException("ServiceRegistry is not running");
        }

        try {
            String serviceName = instance.getServiceName();
            String instanceId = instance.getInstanceId();

            // Safely handle serviceName and instanceId to ensure consistency with keys used during registration
            String safeServiceName = RegistryKeys.validateAndSanitizeServiceName(serviceName);
            String safeInstanceId = RegistryKeys.validateAndSanitizeInstanceId(instanceId);

            // Use Lua script for atomic deregistration
            String servicesKey = registryKeys.getServicesIndexKey();
            String heartbeatKey = registryKeys.getServiceHeartbeatsKey(safeServiceName);
            String instanceKey = registryKeys.getServiceInstanceKey(safeServiceName, safeInstanceId);

            luaExecutor.executeDeregisterInstance(servicesKey, heartbeatKey, instanceKey, safeServiceName, safeInstanceId);

            // Clean up local state
            stateManager.removeInstanceState(safeServiceName, safeInstanceId);

            // Notify service change
            notifyServiceChange(safeServiceName, ServiceChangeAction.REMOVED, instance);

            logger.info("Service instance deregistered: {}:{}", safeServiceName, safeInstanceId);

        } catch (Exception e) {
            logger.error("Failed to deregister service instance: {}:{}",
                    instance.getServiceName(), instance.getInstanceId(), e);
            throw new RuntimeException("Service deregistration failed", e);
        }
    }

    @Override
    public void sendHeartbeat(ServiceInstance instance) {
        if (!running) {
            return; // Heartbeat can fail silently
        }

        try {
            processInstanceHeartbeat(instance);
        } catch (Exception e) {
            logger.warn("Failed to send heartbeat for service instance: {}:{}",
                    instance.getServiceName(), instance.getInstanceId(), e);
        }
    }

    @Override
    public void batchSendHeartbeats(List<ServiceInstance> instances) {
        if (!running || instances.isEmpty()) {
            return;
        }

        for (ServiceInstance instance : instances) {
            try {
                processInstanceHeartbeat(instance);
            } catch (Exception e) {
                logger.warn("Failed to send heartbeat for service instance: {}:{}",
                        instance.getServiceName(), instance.getInstanceId(), e);
            }
        }
    }

    // ==================== ServiceRegistry interface implementation (alias methods) ====================

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
            return;
        }

        running = true;
        executorService = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "RedisServiceProvider");
            t.setDaemon(true);
            return t;
        });

        // Start scheduled cleanup task
        cleanupTask = executorService.scheduleWithFixedDelay(
                this::cleanupExpiredInstances, 60, 30, TimeUnit.SECONDS);

        logger.info("RedisServiceProvider started");
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        if (cleanupTask != null) {
            cleanupTask.cancel(false);
        }

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        logger.info("RedisServiceProvider stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Process instance heartbeat (using smart heartbeat strategy, distinguishes metadata and metrics)
     */
    private void processInstanceHeartbeat(ServiceInstance instance) {
        String serviceName = instance.getServiceName();
        String instanceId = instance.getInstanceId();

        // Safely handle serviceName and instanceId
        String safeServiceName = RegistryKeys.validateAndSanitizeServiceName(serviceName);
        String safeInstanceId = RegistryKeys.validateAndSanitizeInstanceId(instanceId);

        // ========== 1. Collect current data ==========
        Map<String, Object> currentMetrics = metricsManager != null ?
                metricsManager.collectMetrics(false) : Collections.emptyMap();

        Map<String, Object> currentMetadata = new HashMap<>(instance.getMetadata());

        // ========== 2. Decision: whether to update metrics ==========
        UpdateDecision metricsDecision = currentMetrics.isEmpty() ?
                UpdateDecision.NO_UPDATE :
                stateManager.shouldUpdateMetrics(safeServiceName, safeInstanceId, currentMetrics);

        // ========== 3. Decision: whether to update metadata (rarely triggered) ==========
        UpdateDecision metadataDecision = stateManager.shouldUpdateMetadata(
                safeServiceName, safeInstanceId, currentMetadata);

        // ========== 4. Merge decisions and execute ==========
        UpdateDecision finalDecision = mergeDecisions(metricsDecision, metadataDecision);

        if (finalDecision != UpdateDecision.NO_UPDATE) {
            executeUpdate(instance, safeServiceName, safeInstanceId,
                    finalDecision, currentMetadata, currentMetrics);
        } else {
            // No update needed at all (neither heartbeat nor data update)
            logger.trace("No update needed for {}:{}", safeServiceName, safeInstanceId);
        }
    }

    /**
     * Merge metrics and metadata update decisions
     *
     * @param metricsDecision metrics update decision
     * @param metadataDecision metadata update decision
     * @return merged decision
     */
    private UpdateDecision mergeDecisions(UpdateDecision metricsDecision,
                                          UpdateDecision metadataDecision) {
        // Priority: FULL_UPDATE > METADATA_UPDATE > METRICS_UPDATE > HEARTBEAT_ONLY > NO_UPDATE

        // If both metadata and metrics need to be updated
        if (metadataDecision == UpdateDecision.METADATA_UPDATE &&
                (metricsDecision == UpdateDecision.METRICS_UPDATE ||
                        metricsDecision == UpdateDecision.HEARTBEAT_ONLY)) {
            return UpdateDecision.FULL_UPDATE;
        }

        // Metadata takes priority (because it changes less, more important)
        if (metadataDecision == UpdateDecision.METADATA_UPDATE) {
            return UpdateDecision.METADATA_UPDATE;
        }

        // Metrics update
        if (metricsDecision == UpdateDecision.METRICS_UPDATE) {
            return UpdateDecision.METRICS_UPDATE;
        }

        // Only heartbeat needed
        if (metricsDecision == UpdateDecision.HEARTBEAT_ONLY ||
                metadataDecision == UpdateDecision.HEARTBEAT_ONLY) {
            return UpdateDecision.HEARTBEAT_ONLY;
        }

        return UpdateDecision.NO_UPDATE;
    }

    /**
     * Execute update operation
     */
    private void executeUpdate(ServiceInstance instance,
                               String safeServiceName,
                               String safeInstanceId,
                               UpdateDecision decision,
                               Map<String, Object> metadata,
                               Map<String, Object> metrics) {
        try {
            long currentTime = System.currentTimeMillis();

            String heartbeatKey = registryKeys.getServiceHeartbeatsKey(safeServiceName);
            String instanceKey = registryKeys.getServiceInstanceKey(safeServiceName, safeInstanceId);

            // ========== Determine update mode and data ==========
            String updateMode;
            String metadataJson = null;
            String metricsJson = null;

            switch (decision) {
                case HEARTBEAT_ONLY:
                    updateMode = "heartbeat_only";
                    break;

                case METRICS_UPDATE:
                    updateMode = "metrics_update";
                    metricsJson = objectMapper.writeValueAsString(metrics);
                    break;

                case METADATA_UPDATE:
                    updateMode = "metadata_update";
                    metadataJson = objectMapper.writeValueAsString(metadata);
                    break;

                case FULL_UPDATE:
                    updateMode = "full_update";
                    metadataJson = objectMapper.writeValueAsString(metadata);
                    metricsJson = objectMapper.writeValueAsString(metrics);
                    break;

                default:
                    return;  // NO_UPDATE
            }

            // ========== Execute update script (and refresh TTL, only for ephemeral instances) ==========
            luaExecutor.executeHeartbeatUpdate(
                    heartbeatKey, instanceKey, safeInstanceId,
                    currentTime, updateMode, metadataJson, metricsJson,
                    config.getHeartbeatTimeoutSeconds()
            );

            // ========== Update local state ==========
            switch (decision) {
                case METRICS_UPDATE:
                    stateManager.markMetricsUpdateCompleted(safeServiceName, safeInstanceId, metrics);
                    break;
                case METADATA_UPDATE:
                    stateManager.markMetadataUpdateCompleted(safeServiceName, safeInstanceId, metadata);
                    break;
                case FULL_UPDATE:
                    stateManager.markMetricsUpdateCompleted(safeServiceName, safeInstanceId, metrics);
                    stateManager.markMetadataUpdateCompleted(safeServiceName, safeInstanceId, metadata);
                    break;
                case HEARTBEAT_ONLY:
                    stateManager.markHeartbeatOnlyCompleted(safeServiceName, safeInstanceId);
                    break;
            }

            logger.debug("Heartbeat processed for {}:{} with mode: {}",
                    safeServiceName, safeInstanceId, updateMode);

            // Send UPDATED event so consumers can perceive instance attribute changes
            if (!"heartbeat_only".equals(updateMode)) {
                notifyServiceChange(safeServiceName, ServiceChangeAction.UPDATED, instance);
            }

        } catch (Exception e) {
            logger.error("Failed to execute update for {}:{}",
                    safeServiceName, safeInstanceId, e);
            throw new RuntimeException("Failed to execute update", e);
        }
    }

    /**
     * Cleanup expired service instances
     */
    private void cleanupExpiredInstances() {
        try {
            // Get all services
            RSet<String> servicesSet = redissonClient.getSet(registryKeys.getServicesIndexKey(), org.redisson.client.codec.StringCodec.INSTANCE);
            Set<String> services = servicesSet.readAll();

            for (String serviceName : services) {
                cleanupExpiredInstancesForService(serviceName);
            }

        } catch (Exception e) {
            logger.error("Failed to cleanup expired instances", e);
        }
    }

    /**
     * Cleanup expired instances for a specified service
     */
    private void cleanupExpiredInstancesForService(String serviceName) {
        try {
            long currentTime = System.currentTimeMillis();
            long timeoutMs = config.getHeartbeatTimeoutSeconds() * 1000L;

            String heartbeatKey = registryKeys.getServiceHeartbeatsKey(serviceName);
            String keyPrefix = config.getKeyPrefix() != null ? config.getKeyPrefix() : "registry";

            // Call cleanup script with snapshots, result is [id1, json1, id2, json2, ...]
            List<Object> result = luaExecutor.executeCleanupExpiredInstancesWithSnapshots(
                    heartbeatKey, serviceName, currentTime, timeoutMs, keyPrefix
            );

            if (!result.isEmpty()) {
                int cleaned = 0;
                for (int i = 0; i + 1 < result.size(); i += 2) {
                    String instanceId = String.valueOf(result.get(i));
                    String json = String.valueOf(result.get(i + 1));
                    cleaned++;

                    // Clean up local state
                    stateManager.removeInstanceState(serviceName, instanceId);

                    // Convert JSON snapshot to ServiceInstance for notification
                    ServiceInstance snapshot = buildInstanceFromSnapshot(serviceName, instanceId, json);
                    if (snapshot != null) {
                        notifyServiceChange(serviceName, ServiceChangeAction.REMOVED, snapshot);
                    } else {
                        // Fallback: notify with ID only
                        notifyServiceChangeById(serviceName, ServiceChangeAction.REMOVED, instanceId);
                    }
                }

                // If heartbeat set is empty, remove from service index (avoid residual)
                try {
                    RScoredSortedSet<String> set = redissonClient.getScoredSortedSet(heartbeatKey);
                    if (set.size() == 0) {
                        RSet<String> servicesSet = redissonClient.getSet(registryKeys.getServicesIndexKey(), org.redisson.client.codec.StringCodec.INSTANCE);
                        servicesSet.remove(serviceName);
                    }
                } catch (Exception ignore) {}

                logger.info("Cleaned up {} expired instances for service: {}", cleaned, serviceName);
            }

        } catch (Exception e) {
            logger.error("Failed to cleanup expired instances for service: {}", serviceName, e);
        }
    }

    /**
     * Build ServiceInstance snapshot from instance Hash JSON returned by Lua
     */
    private ServiceInstance buildInstanceFromSnapshot(String serviceName, String instanceId, String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>(){});
            String host = (String) map.get("host");
            int port = map.get("port") != null ? Integer.parseInt(map.get("port").toString()) : 0;
            String protocolName = (String) map.get("protocol");
            Protocol protocol = protocolName != null ? StandardProtocol.fromName(protocolName) : StandardProtocol.HTTP;
            boolean enabled = map.get("enabled") == null || Boolean.parseBoolean(map.get("enabled").toString());
            boolean healthy = map.get("healthy") == null || Boolean.parseBoolean(map.get("healthy").toString());
            int weight = map.get("weight") != null ? Integer.parseInt(map.get("weight").toString()) : 1;

            Map<String, String> metadata = new HashMap<>();
            Object metadataJson = map.get("metadata");
            if (metadataJson instanceof String) {
                try {
                    Map<String, Object> md = objectMapper.readValue((String) metadataJson, new TypeReference<Map<String, Object>>(){});
                    for (Map.Entry<String, Object> e : md.entrySet()) {
                        if (e.getValue() != null) {
                            metadata.put(e.getKey(), e.getValue().toString());
                        }
                    }
                } catch (Exception ignore) {}
            }

            return DefaultServiceInstance.builder()
                    .serviceName(serviceName)
                    .instanceId(instanceId)
                    .host(host)
                    .port(port)
                    .protocol(protocol)
                    .enabled(enabled)
                    .healthy(healthy)
                    .weight(weight)
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            logger.warn("Failed to build snapshot instance for {}:{}", serviceName, instanceId, e);
            return null;
        }
    }

    // prepareInstanceData migrated to InstanceEntryCodec

    /**
     * Notify service change
     */
    private void notifyServiceChange(String serviceName, ServiceChangeAction action, ServiceInstance instance) {
        try {
            RTopic topic = redissonClient.getTopic(config.getServiceChangeChannelKey(serviceName), new JsonJacksonCodec());

            ServiceChangeEvent.InstanceSnapshot snap = new ServiceChangeEvent.InstanceSnapshot();
            snap.setServiceName(instance.getServiceName());
            snap.setInstanceId(instance.getInstanceId());
            snap.setHost(instance.getHost());
            snap.setPort(instance.getPort());
            snap.setProtocol(instance.getProtocol().getName());
            snap.setEnabled(instance.isEnabled());
            snap.setHealthy(instance.isHealthy());
            snap.setWeight(instance.getWeight());
            snap.setMetadata(instance.getMetadata());

            ServiceChangeEvent evt = new ServiceChangeEvent(serviceName, action, instance.getInstanceId(), System.currentTimeMillis(), snap);

            topic.publish(evt);

        } catch (Exception e) {
            logger.warn("Failed to notify service change: {} {} {}",
                    serviceName, action, instance.getInstanceId(), e);
        }
    }

    /**
     * Notify service change (by instance ID only)
     */
    private void notifyServiceChangeById(String serviceName, ServiceChangeAction action, String instanceId) {
        try {
            RTopic topic = redissonClient.getTopic(config.getServiceChangeChannelKey(serviceName), new JsonJacksonCodec());
            ServiceChangeEvent evt = new ServiceChangeEvent(serviceName, action, instanceId, System.currentTimeMillis(), null);
            topic.publish(evt);

        } catch (Exception e) {
            logger.warn("Failed to notify service change: {} {} {}", serviceName, action, instanceId, e);
        }
    }

    /**
     * Create a safe ServiceInstance copy with sanitized serviceName and instanceId
     */
    private ServiceInstance createSafeInstance(ServiceInstance original, String safeServiceName, String safeInstanceId) {
        return DefaultServiceInstance.builder()
                .serviceName(safeServiceName)
                .instanceId(safeInstanceId)
                .host(original.getHost())
                .port(original.getPort())
                .protocol(original.getProtocol())
                .enabled(original.isEnabled())
                .healthy(original.isHealthy())
                .weight(original.getWeight())
                .metadata(new HashMap<>(original.getMetadata()))
                .build();
    }

    /**
     * Get configuration
     */
    public ServiceProviderConfig getConfig() {
        return config;
    }

    /**
     * Get heartbeat configuration
     */
    public HeartbeatConfig getHeartbeatConfig() {
        return heartbeatConfig;
    }

    /**
     * Get state manager
     */
    public HeartbeatStateManager getStateManager() {
        return stateManager;
    }

    /**
     * Get metrics manager
     */
    public MetricsCollectionManager getMetricsManager() {
        return metricsManager;
    }

    /**
     * Get RegistryKeys
     */
    public RegistryKeys getRegistryKeys() {
        return registryKeys;
    }
}
