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
 * 基于Redis的服务提供者实现
 * 支持三级存储结构 + 智能心跳策略（通过配置开关启用）
 *
 * <p>实现了两个接口：</p>
 * <ul>
 *   <li>{@link ServiceProvider} - 业务角色视角</li>
 *   <li>{@link ServiceRegistry} - 技术操作视角</li>
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

            // 安全处理 serviceName，防止冒号等字符导致 key 解析问题
            String safeServiceName = RegistryKeys.validateAndSanitizeServiceName(serviceName);

            // 安全处理 instanceId，防止冒号等字符导致 key 解析问题
            String safeInstanceId = RegistryKeys.validateAndSanitizeInstanceId(instanceId);

            // 如果清理后的值发生变化，创建新的实例对象
            if (!serviceName.equals(safeServiceName) || !instanceId.equals(safeInstanceId)) {
                instance = createSafeInstance(instance, safeServiceName, safeInstanceId);
                logger.info("Instance sanitized: serviceName '{}' -> '{}', instanceId '{}' -> '{}'",
                    serviceName, safeServiceName, instanceId, safeInstanceId);
                // 更新局部变量
                serviceName = safeServiceName;
                instanceId = safeInstanceId;
            }

            long currentTime = System.currentTimeMillis();

            // 准备实例数据
            Map<String, Object> instanceData = InstanceEntryCodec.buildInstanceData(instance, currentTime);

            // 收集初始指标
            if (metricsManager != null) {
                Map<String, Object> metrics = metricsManager.collectMetrics(true);
                instanceData.put("metrics", metrics);
            }

            // 使用Lua脚本执行原子注册
            String servicesKey = registryKeys.getServicesIndexKey();
            String heartbeatKey = registryKeys.getServiceHeartbeatsKey(serviceName);
            String instanceKey = registryKeys.getServiceInstanceKey(serviceName, instanceId);

            // 判断是否已存在，决定事件类型（ADDED 或 UPDATED）
            boolean existed = false;
            try {
                RMap<String, String> imap = redissonClient.getMap(instanceKey);
                existed = imap.isExists();
            } catch (Exception ignore) {
                // 非关键路径，存在异常则回退到 ADDED
            }

            luaExecutor.executeRegisterInstance(
                    servicesKey, heartbeatKey, instanceKey,
                    serviceName, instanceId, currentTime,
                    objectMapper.writeValueAsString(instanceData), config.getHeartbeatTimeoutSeconds()
            );

            // 通知服务变更（首次为 ADDED，重复注册视为 UPDATED）
            notifyServiceChange(serviceName, existed ? ServiceChangeAction.UPDATED : ServiceChangeAction.ADDED, instance);

            // 初始化心跳状态管理器中的metadata hash，确保后续的变更检测能正常工作
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

            // 安全处理 serviceName 和 instanceId，确保与注册时使用的key一致
            String safeServiceName = RegistryKeys.validateAndSanitizeServiceName(serviceName);
            String safeInstanceId = RegistryKeys.validateAndSanitizeInstanceId(instanceId);

            // 使用Lua脚本执行原子注销
            String servicesKey = registryKeys.getServicesIndexKey();
            String heartbeatKey = registryKeys.getServiceHeartbeatsKey(safeServiceName);
            String instanceKey = registryKeys.getServiceInstanceKey(safeServiceName, safeInstanceId);

            luaExecutor.executeDeregisterInstance(servicesKey, heartbeatKey, instanceKey, safeServiceName, safeInstanceId);

            // 清理本地状态
            stateManager.removeInstanceState(safeServiceName, safeInstanceId);

            // 通知服务变更
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
            return; // 心跳可以静默失败
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

    // ==================== ServiceRegistry 接口实现（别名方法） ====================

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
            return;
        }

        running = true;
        executorService = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "RedisServiceProvider");
            t.setDaemon(true);
            return t;
        });

        // 启动定时清理任务
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
     * 处理实例心跳（使用智能心跳策略，区分 metadata 和 metrics）
     */
    private void processInstanceHeartbeat(ServiceInstance instance) {
        String serviceName = instance.getServiceName();
        String instanceId = instance.getInstanceId();

        // 安全处理 serviceName 和 instanceId
        String safeServiceName = RegistryKeys.validateAndSanitizeServiceName(serviceName);
        String safeInstanceId = RegistryKeys.validateAndSanitizeInstanceId(instanceId);

        // ========== 1. 收集当前数据 ==========
        Map<String, Object> currentMetrics = metricsManager != null ?
                metricsManager.collectMetrics(false) : Collections.emptyMap();

        Map<String, Object> currentMetadata = new HashMap<>(instance.getMetadata());

        // ========== 2. 决策：是否更新 metrics ==========
        UpdateDecision metricsDecision = currentMetrics.isEmpty() ?
                UpdateDecision.NO_UPDATE :
                stateManager.shouldUpdateMetrics(safeServiceName, safeInstanceId, currentMetrics);

        // ========== 3. 决策：是否更新 metadata（极少触发）==========
        UpdateDecision metadataDecision = stateManager.shouldUpdateMetadata(
                safeServiceName, safeInstanceId, currentMetadata);

        // ========== 4. 合并决策并执行 ==========
        UpdateDecision finalDecision = mergeDecisions(metricsDecision, metadataDecision);

        if (finalDecision != UpdateDecision.NO_UPDATE) {
            executeUpdate(instance, safeServiceName, safeInstanceId,
                    finalDecision, currentMetadata, currentMetrics);
        } else {
            // 完全不需要更新（既不需要心跳，也不需要数据更新）
            logger.trace("No update needed for {}:{}", safeServiceName, safeInstanceId);
        }
    }

    /**
     * 合并 metrics 和 metadata 的更新决策
     *
     * @param metricsDecision metrics 更新决策
     * @param metadataDecision metadata 更新决策
     * @return 合并后的决策
     */
    private UpdateDecision mergeDecisions(UpdateDecision metricsDecision,
                                          UpdateDecision metadataDecision) {
        // 优先级：FULL_UPDATE > METADATA_UPDATE > METRICS_UPDATE > HEARTBEAT_ONLY > NO_UPDATE

        // 如果 metadata 和 metrics 都需要更新
        if (metadataDecision == UpdateDecision.METADATA_UPDATE &&
                (metricsDecision == UpdateDecision.METRICS_UPDATE ||
                        metricsDecision == UpdateDecision.HEARTBEAT_ONLY)) {
            return UpdateDecision.FULL_UPDATE;
        }

        // metadata 优先（因为变化少，更重要）
        if (metadataDecision == UpdateDecision.METADATA_UPDATE) {
            return UpdateDecision.METADATA_UPDATE;
        }

        // metrics 更新
        if (metricsDecision == UpdateDecision.METRICS_UPDATE) {
            return UpdateDecision.METRICS_UPDATE;
        }

        // 只需要心跳
        if (metricsDecision == UpdateDecision.HEARTBEAT_ONLY ||
                metadataDecision == UpdateDecision.HEARTBEAT_ONLY) {
            return UpdateDecision.HEARTBEAT_ONLY;
        }

        return UpdateDecision.NO_UPDATE;
    }

    /**
     * 执行更新操作
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

            // ========== 决定更新模式和数据 ==========
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

            // ========== 执行更新脚本（并刷新TTL，仅对临时实例） ==========
            luaExecutor.executeHeartbeatUpdate(
                    heartbeatKey, instanceKey, safeInstanceId,
                    currentTime, updateMode, metadataJson, metricsJson,
                    config.getHeartbeatTimeoutSeconds()
            );

            // ========== 更新本地状态 ==========
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

            // 发送 UPDATED 事件，便于消费者感知实例属性变化
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
     * 清理过期的服务实例
     */
    private void cleanupExpiredInstances() {
        try {
            // 获取所有服务
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
     * 清理指定服务的过期实例
     */
    private void cleanupExpiredInstancesForService(String serviceName) {
        try {
            long currentTime = System.currentTimeMillis();
            long timeoutMs = config.getHeartbeatTimeoutSeconds() * 1000L;

            String heartbeatKey = registryKeys.getServiceHeartbeatsKey(serviceName);
            String keyPrefix = config.getKeyPrefix() != null ? config.getKeyPrefix() : "registry";

            // 调用带快照的清理脚本，结果为 [id1, json1, id2, json2, ...]
            List<Object> result = luaExecutor.executeCleanupExpiredInstancesWithSnapshots(
                    heartbeatKey, serviceName, currentTime, timeoutMs, keyPrefix
            );

            if (!result.isEmpty()) {
                int cleaned = 0;
                for (int i = 0; i + 1 < result.size(); i += 2) {
                    String instanceId = String.valueOf(result.get(i));
                    String json = String.valueOf(result.get(i + 1));
                    cleaned++;

                    // 清理本地状态
                    stateManager.removeInstanceState(serviceName, instanceId);

                    // 将 JSON 快照转换为 ServiceInstance，用于通知
                    ServiceInstance snapshot = buildInstanceFromSnapshot(serviceName, instanceId, json);
                    if (snapshot != null) {
                        notifyServiceChange(serviceName, ServiceChangeAction.REMOVED, snapshot);
                    } else {
                        // 回退：仅用ID通知
                        notifyServiceChangeById(serviceName, ServiceChangeAction.REMOVED, instanceId);
                    }
                }

                // 若心跳集为空则从服务索引移除（避免残留）
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
     * 根据 Lua 返回的实例 Hash JSON 构建 ServiceInstance 快照
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
     * 通知服务变更
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
     * 通知服务变更（仅根据实例ID）
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
     * 创建安全的ServiceInstance副本，使用清理后的serviceName和instanceId
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
     * 获取配置
     */
    public ServiceProviderConfig getConfig() {
        return config;
    }

    /**
     * 获取心跳配置
     */
    public HeartbeatConfig getHeartbeatConfig() {
        return heartbeatConfig;
    }

    /**
     * 获取状态管理器
     */
    public HeartbeatStateManager getStateManager() {
        return stateManager;
    }

    /**
     * 获取指标管理器
     */
    public MetricsCollectionManager getMetricsManager() {
        return metricsManager;
    }

    /**
     * 获取RegistryKeys
     */
    public RegistryKeys getRegistryKeys() {
        return registryKeys;
    }
}
