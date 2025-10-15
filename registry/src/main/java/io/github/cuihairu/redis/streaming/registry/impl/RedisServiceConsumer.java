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
 * 基于Redis的服务消费者实现
 * 支持健康检测和状态报告功能（通过配置开关启用）
 */
public class RedisServiceConsumer implements ServiceDiscovery, ServiceConsumer {

    private static final Logger logger = LoggerFactory.getLogger(RedisServiceConsumer.class);

    private final RedissonClient redissonClient;
    /**
     * -- GETTER --
     *  获取配置
     *
     */
    @Getter
    private final ServiceConsumerConfig config;
    private volatile boolean running = false;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RegistryLuaScriptExecutor luaExecutor;

    // 监听器管理
    private final Map<String, Set<ServiceChangeListener>> listeners = new ConcurrentHashMap<>();
    private final Map<String, RTopic> subscriptions = new ConcurrentHashMap<>();

    /**
     * -- GETTER --
     *  获取健康检测管理器
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
     * 初始化健康检测管理器（如果配置启用）
     */
    private void initializeHealthCheckManager() {
        if (config.isEnableHealthCheck()) {
            // 创建标准健康检查器
            StandardHealthChecker standardHealthChecker = new StandardHealthChecker();

            // 注册协议特定的健康检查器（使用配置的超时时间）
            int timeout = config.getHealthCheckTimeout();
            HttpHealthChecker httpHealthChecker = new HttpHealthChecker(timeout, timeout, "/health");
            TcpHealthChecker tcpHealthChecker = new TcpHealthChecker(timeout);
            WebSocketHealthChecker webSocketHealthChecker = new WebSocketHealthChecker(timeout);

            // 创建健康检测管理器
            this.healthCheckManager = new HealthCheckManager(
                standardHealthChecker,
                this::reportHealthStatus,
                config.getHealthCheckInterval(),
                config.getHealthCheckTimeUnit()
            );

            // 注册协议健康检查器
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.HTTP, httpHealthChecker);
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.HTTPS, httpHealthChecker);
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.TCP, tcpHealthChecker);
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.WS, webSocketHealthChecker);
            healthCheckManager.registerProtocolHealthChecker(StandardProtocol.WSS, webSocketHealthChecker);

            logger.info("Health check manager initialized with {} second interval", config.getHealthCheckInterval());
        }
    }

    /**
     * 报告健康状态变化
     */
    private void reportHealthStatus(String instanceId, boolean isHealthy) {
        ServiceInstance instance = discoveredInstances.get(instanceId);
        if (instance != null) {
            logger.info("Service instance {} health status changed to: {}", instanceId, isHealthy);

            // 更新实例的健康状态
            if (instance instanceof DefaultServiceInstance) {
                ServiceInstance updatedInstance = DefaultServiceInstance.builder()
                    .serviceName(instance.getServiceName())
                    .instanceId(instance.getInstanceId())
                    .host(instance.getHost())
                    .port(instance.getPort())
                    .protocol(instance.getProtocol())
                    .enabled(instance.isEnabled())
                    .healthy(isHealthy)  // 更新健康状态
                    .weight(instance.getWeight())
                    .metadata(instance.getMetadata())
                    .build();

                discoveredInstances.put(instanceId, updatedInstance);
            }

            // 触发服务变更事件
            notifyHealthStatusChange(instance.getServiceName(), instanceId, isHealthy);
        }
    }

    /**
     * 通知健康状态变化
     */
    private void notifyHealthStatusChange(String serviceName, String instanceId, boolean isHealthy) {
        Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners != null && !serviceListeners.isEmpty()) {
            ServiceChangeAction action = isHealthy ?
                ServiceChangeAction.HEALTH_RECOVERY : ServiceChangeAction.HEALTH_FAILURE;
            ServiceInstance instance = discoveredInstances.get(instanceId);

            if (instance != null) {
                // 获取当前所有实例
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
            // 使用三级存储结构获取服务实例
            String heartbeatsKey = config.getHeartbeatKey(serviceName);
            RScoredSortedSet<String> heartbeatsSet = redissonClient.getScoredSortedSet(heartbeatsKey, StringCodec.INSTANCE);

            // 获取所有未过期的实例ID
            long currentTime = System.currentTimeMillis();
            long heartbeatTimeoutMs = config.getHeartbeatTimeoutSeconds() * 1000L;
            long expiredTime = currentTime - heartbeatTimeoutMs;
            Collection<String> activeInstanceIds = heartbeatsSet.valueRange(expiredTime, false, Double.MAX_VALUE, false);

            List<ServiceInstance> instances = new ArrayList<>();

            for (String instanceId : activeInstanceIds) {
                try {
                    // 从Hash结构获取实例详情（使用StringCodec以匹配Lua脚本）
                    String instanceKey = config.getServiceInstanceKey(serviceName, instanceId);
                    RMap<String, String> instanceMap = redissonClient.getMap(instanceKey, StringCodec.INSTANCE);
                    Map<String, String> instanceData = instanceMap.readAllMap();

                    if (!instanceData.isEmpty()) {
                        ServiceInstance instance = buildServiceInstance(serviceName, instanceId, instanceData);
                        if (instance != null) {
                            instances.add(instance);

                            // 缓存发现的实例
                            discoveredInstances.put(instanceId, instance);

                            // 如果启用健康检测，注册健康检查器
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
        
        // 过滤健康且启用的实例
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

        // 添加监听器
        listeners.computeIfAbsent(serviceName, k -> ConcurrentHashMap.newKeySet()).add(listener);

        // 如果是第一个监听器，创建Redis订阅
        if (!subscriptions.containsKey(serviceName)) {
            RTopic topic = redissonClient.getTopic(
                config.getServiceChangeChannelKey(serviceName), new org.redisson.codec.JsonJacksonCodec());

            topic.addListener(io.github.cuihairu.redis.streaming.registry.event.ServiceChangeEvent.class, (channel, message) -> {
                handleServiceChangeEvent(serviceName, message);
            });

            subscriptions.put(serviceName, topic);
            logger.info("Subscribed to service changes for: {}", serviceName);
        }

        // 立即通知当前的服务实例列表
        try {
            List<ServiceInstance> currentInstances = discoverHealthy(serviceName);
            if (!currentInstances.isEmpty()) {
                // 通知每个实例作为"current"事件
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

            // 如果没有监听器了，取消Redis订阅
            if (serviceListeners.isEmpty()) {
                listeners.remove(serviceName);

                RTopic topic = subscriptions.remove(serviceName);
                if (topic != null) {
                    topic.removeAllListeners();
                    logger.info("Unsubscribed from service changes for: {}", serviceName);
                }

                // 移除健康检测
                if (config.isEnableHealthCheck() && healthCheckManager != null) {
                    // 移除该服务的所有实例的健康检查器
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

        // 启动健康检测管理器
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

        // 停止健康检测
        if (healthCheckManager != null) {
            healthCheckManager.stopAll();
        }

        // 清理所有订阅
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
     * 处理服务变更事件
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

            // 解析action
            ServiceChangeAction action = message.getAction();

            // 获取当前所有健康的实例列表
            List<ServiceInstance> allInstances = discoverHealthy(serviceName);

            // 构建变更的实例
            ServiceInstance changedInstance = null;
            if (action == ServiceChangeAction.REMOVED) {
                // 对于删除事件，从消息快照构建实例信息
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
                // 对于添加和更新事件，从当前实例列表中查找
                changedInstance = allInstances.stream()
                    .filter(instance -> instanceId.equals(instance.getInstanceId()))
                    .findFirst()
                    .orElse(null);
            }

            // 通知所有监听器
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
     * 构建服务实例对象
     */
    private ServiceInstance buildServiceInstance(String serviceName, String instanceId, Map<String, String> data) {
        ServiceInstance si = InstanceEntryCodec.parseInstance(serviceName, instanceId, data);
        if (si == null) {
            logger.warn("Invalid instance data for {}:{}", serviceName, instanceId);
        }
        return si;
    }
    
    /**
     * 解析协议
     */
    private Protocol parseProtocol(String protocolName) {
        if (protocolName == null) {
            return StandardProtocol.HTTP;
        }
        try {
            // 尝试按标准协议解析（与写入端一致）
            return StandardProtocol.fromName(protocolName);
        } catch (IllegalArgumentException e) {
            // 兜底为 HTTP，避免因为未知协议导致发现失败
            return StandardProtocol.HTTP;
        }
    }
    
    /**
     * 检查实例心跳是否有效
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
     * 列出所有服务名称
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
     * 手动触发服务发现
     */
    public void refreshServiceInstances(String serviceName) {
        if (running) {
            discover(serviceName);
        }
    }

    /**
     * 获取实例的健康状态
     */
    public boolean isInstanceHealthy(String instanceId) {
        if (healthCheckManager != null) {
            return healthCheckManager.isInstanceHealthy(instanceId);
        }

        // 如果没有启用健康检测，从缓存的实例中获取
        ServiceInstance instance = discoveredInstances.get(instanceId);
        return instance != null && instance.isHealthy();
    }

    /**
     * 获取已发现的服务实例数量
     */
    public int getDiscoveredInstanceCount() {
        return discoveredInstances.size();
    }

    /**
     * 获取健康检查器数量
     */
    public int getActiveHealthCheckersCount() {
        return healthCheckManager != null ? healthCheckManager.getHealthCheckerCount() : 0;
    }

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
     *   <li>"field:&lt;": "value" - 小于</li>
     *   <li>"field:&lt;=": "value" - 小于等于</li>
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
     * filters.put("cpu_usage:&lt;", "80");    // CPU使用率 &lt; 80
     *
     * // ✅ 推荐：字符串相等
     * filters.put("region", "us-east-1");  // 精确匹配
     * filters.put("status:!=", "down");    // 状态不等于
     *
     * // ⚠️ 谨慎：字符串大小比较（字典序）
     * filters.put("zone:>", "zone-a");     // 字典序比较
     *
     * // ❌ 不支持：版本号比较（请使用精确匹配或版本标签）
     * // filters.put("version:>=", "1.10.0");  // 错误！
     * filters.put("version", "1.10.0");    // 正确：精确匹配
     * </pre>
     *
     * @return 匹配的服务实例列表
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
            // 将 metadata 过滤条件转为 JSON
            String metadataFiltersJson = objectMapper.writeValueAsString(metadataFilters);

            // 使用 Lua 脚本执行过滤
            String heartbeatsKey = config.getHeartbeatKey(serviceName);
            long currentTime = System.currentTimeMillis();
            long heartbeatTimeoutMs = config.getHeartbeatTimeoutSeconds() * 1000L;

            // 使用新接口（metadata-only），metricsFilters 传 null
            List<String> matchedInstanceIds = luaExecutor.executeGetInstancesByFilters(
                    heartbeatsKey,
                    config.getRegistryKeys().getKeyPrefix(),
                    serviceName,
                    currentTime,
                    heartbeatTimeoutMs,
                    metadataFiltersJson,
                    null
            );

            // 根据实例ID加载完整的实例信息
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

                            // 缓存发现的实例
                            discoveredInstances.put(instanceId, instance);

                            // 如果启用健康检测，注册健康检查器
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
     * 根据 metadata 过滤获取健康的服务实例
     *
     * @param serviceName 服务名称
     * @param metadataFilters metadata过滤条件（AND关系）
     * @return 匹配且健康的服务实例列表
     */
    @Override
    public List<ServiceInstance> discoverHealthyByMetadata(String serviceName, Map<String, String> metadataFilters) {
        List<ServiceInstance> allMatched = discoverByMetadata(serviceName, metadataFilters);

        // 过滤健康且启用的实例
        return allMatched.stream()
                .filter(instance -> instance.isEnabled() && instance.isHealthy())
                .filter(this::isInstanceHeartbeatValid)
                .collect(Collectors.toList());
    }

    // ==================== 扩展：支持 metrics 过滤 ====================

    /**
     * 使用 metadata 与 metrics 组合进行服务端过滤
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
    // ==================== ServiceConsumer 接口实现（委托方法） ====================

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
