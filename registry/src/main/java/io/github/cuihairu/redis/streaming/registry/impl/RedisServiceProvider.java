package io.github.cuihairu.redis.streaming.registry.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
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
import org.redisson.api.*;
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
        this.metricsManager = metricsManager;
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
            Map<String, Object> instanceData = prepareInstanceData(instance, currentTime);

            // 收集初始指标
            if (metricsManager != null) {
                Map<String, Object> metrics = metricsManager.collectMetrics(true);
                instanceData.put("metrics", metrics);
            }

            // 使用Lua脚本执行原子注册
            String servicesKey = registryKeys.getServicesIndexKey();
            String heartbeatKey = registryKeys.getServiceHeartbeatsKey(serviceName);
            String instanceKey = registryKeys.getServiceInstanceKey(serviceName, instanceId);

            luaExecutor.executeRegisterInstance(
                    servicesKey, heartbeatKey, instanceKey,
                    serviceName, instanceId, currentTime,
                    objectMapper.writeValueAsString(instanceData), config.getHeartbeatTimeoutSeconds()
            );

            // 通知服务变更
            notifyServiceChange(serviceName, ServiceChangeAction.ADDED, instance);

            logger.info("Service instance registered: {}:{}", serviceName, instanceId);

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

            // ========== 执行更新脚本 ==========
            luaExecutor.executeHeartbeatUpdate(
                    heartbeatKey, instanceKey, safeInstanceId,
                    currentTime, updateMode, metadataJson, metricsJson
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
            RSet<String> servicesSet = redissonClient.getSet(registryKeys.getServicesIndexKey());
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

            List<String> expiredInstances = luaExecutor.executeCleanupExpiredInstances(
                    heartbeatKey, serviceName, currentTime, timeoutMs, keyPrefix
            );

            if (!expiredInstances.isEmpty()) {
                // 清理本地状态
                for (String instanceId : expiredInstances) {
                    stateManager.removeInstanceState(serviceName, instanceId);
                }

                // 通知服务变更
                for (String instanceId : expiredInstances) {
                    notifyServiceChangeById(serviceName, ServiceChangeAction.REMOVED, instanceId);
                }

                logger.info("Cleaned up {} expired instances for service: {}",
                        expiredInstances.size(), serviceName);
            }

        } catch (Exception e) {
            logger.error("Failed to cleanup expired instances for service: {}", serviceName, e);
        }
    }

    /**
     * 准备实例数据
     */
    private Map<String, Object> prepareInstanceData(ServiceInstance instance, long currentTime) {
        Map<String, Object> instanceData = new HashMap<>();
        instanceData.put("host", instance.getHost());
        instanceData.put("port", instance.getPort());
        instanceData.put("protocol", instance.getProtocol().getName());
        instanceData.put("enabled", instance.isEnabled());
        instanceData.put("healthy", instance.isHealthy());
        instanceData.put("weight", instance.getWeight());
        instanceData.put("ephemeral", instance.isEphemeral());  // 添加临时/永久标识
        instanceData.put("metadata", instance.getMetadata());
        instanceData.put("registrationTime", currentTime);
        instanceData.put("lastHeartbeatTime", currentTime);
        instanceData.put("lastMetadataUpdate", currentTime);
        return instanceData;
    }

    /**
     * 通知服务变更
     */
    private void notifyServiceChange(String serviceName, ServiceChangeAction action, ServiceInstance instance) {
        try {
            RTopic topic = redissonClient.getTopic(
                    config.getServiceChangeChannelKey(serviceName));

            Map<String, Object> changeEvent = new HashMap<>();
            changeEvent.put("action", action.getValue());
            changeEvent.put("instanceId", instance.getInstanceId());
            changeEvent.put("timestamp", System.currentTimeMillis());

            // 包含实例详细信息
            Map<String, Object> instanceData = new HashMap<>();
            instanceData.put("serviceName", instance.getServiceName());
            instanceData.put("instanceId", instance.getInstanceId());
            instanceData.put("host", instance.getHost());
            instanceData.put("port", instance.getPort());
            instanceData.put("protocol", instance.getProtocol().getName());
            instanceData.put("enabled", instance.isEnabled());
            instanceData.put("healthy", instance.isHealthy());
            instanceData.put("weight", instance.getWeight());
            instanceData.put("metadata", instance.getMetadata());

            changeEvent.put("instance", instanceData);

            topic.publish(changeEvent);

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
            RTopic topic = redissonClient.getTopic(
                    config.getServiceChangeChannelKey(serviceName));

            Map<String, Object> changeEvent = new HashMap<>();
            changeEvent.put("action", action.getValue());
            changeEvent.put("instanceId", instanceId);
            changeEvent.put("timestamp", System.currentTimeMillis());

            topic.publish(changeEvent);

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
