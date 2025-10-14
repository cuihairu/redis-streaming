package io.github.cuihairu.redis.streaming.registry.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.registry.BaseRedisConfig;
import io.github.cuihairu.redis.streaming.registry.lua.RegistryLuaScriptExecutor;
import org.redisson.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 服务注册中心管理服务
 * 支持三级存储结构
 */
public class RegistryAdminService {

    private static final Logger logger = LoggerFactory.getLogger(RegistryAdminService.class);

    private final RedissonClient redissonClient;
    private final BaseRedisConfig config;
    private final io.github.cuihairu.redis.streaming.registry.keys.RegistryKeys registryKeys;
    private final RegistryLuaScriptExecutor luaExecutor;
    private final ObjectMapper objectMapper;

    public RegistryAdminService(RedissonClient redissonClient, BaseRedisConfig config) {
        this.redissonClient = redissonClient;
        this.config = config != null ? config : new BaseRedisConfig();
        this.luaExecutor = new RegistryLuaScriptExecutor(redissonClient);
        this.objectMapper = new ObjectMapper();
        this.registryKeys = this.config.getRegistryKeys();
    }

    /**
     * 获取所有服务名称
     */
    public Set<String> getAllServices() {
        try {
            RSet<String> servicesSet = redissonClient.getSet(registryKeys.getServicesIndexKey(), org.redisson.client.codec.StringCodec.INSTANCE);
            return new HashSet<>(servicesSet.readAll());
        } catch (Exception e) {
            logger.error("Failed to get all services", e);
            return Collections.emptySet();
        }
    }

    /**
     * 获取服务详细信息
     */
    public ServiceDetails getServiceDetails(String serviceName) {
        return getServiceDetails(serviceName, Duration.ofMinutes(2));
    }

    /**
     * 获取服务详细信息（支持自定义超时）
     */
    public ServiceDetails getServiceDetails(String serviceName, Duration timeout) {
        try {
            ServiceDetails serviceDetails = new ServiceDetails(serviceName);

            // 获取活跃实例
            List<InstanceDetails> instances = getActiveInstances(serviceName, timeout);
            serviceDetails.setInstances(instances);

            // 计算聚合指标
            if (!instances.isEmpty()) {
                Map<String, Object> aggregatedMetrics = calculateAggregatedMetrics(instances);
                serviceDetails.setAggregatedMetrics(aggregatedMetrics);
            }

            return serviceDetails;

        } catch (Exception e) {
            logger.error("Failed to get service details for: {}", serviceName, e);
            ServiceDetails errorDetails = new ServiceDetails(serviceName);
            errorDetails.setInstances(Collections.emptyList());
            return errorDetails;
        }
    }

    /**
     * 获取活跃实例列表
     */
    public List<InstanceDetails> getActiveInstances(String serviceName, Duration timeout) {
        try {
            long currentTime = System.currentTimeMillis();
            long timeoutMs = timeout.toMillis();

            String heartbeatKey = registryKeys.getServiceHeartbeatsKey(serviceName);

            // 获取活跃实例ID和心跳时间
            List<Object> activeData = luaExecutor.executeGetActiveInstances(heartbeatKey, currentTime, timeoutMs);

            List<InstanceDetails> instances = new ArrayList<>();

            // 解析结果并获取实例详情
            for (int i = 0; i < activeData.size(); i += 2) {
                String instanceId = (String) activeData.get(i);
                Object heartbeatTimeObj = activeData.get(i + 1);

                // 兼容 String 和 Number 类型的心跳时间
                long heartbeatTime;
                if (heartbeatTimeObj instanceof String) {
                    heartbeatTime = Long.parseLong((String) heartbeatTimeObj);
                } else if (heartbeatTimeObj instanceof Number) {
                    heartbeatTime = ((Number) heartbeatTimeObj).longValue();
                } else {
                    logger.warn("Unexpected heartbeat time type: {} for instance {}:{}",
                            heartbeatTimeObj.getClass(), serviceName, instanceId);
                    continue;
                }

                try {
                    InstanceDetails instance = getInstanceDetails(serviceName, instanceId);
                    if (instance != null) {
                        instance.setLastHeartbeatTime(heartbeatTime);
                        instances.add(instance);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get details for instance {}:{}", serviceName, instanceId, e);
                }
            }

            return instances;

        } catch (Exception e) {
            logger.error("Failed to get active instances for service: {}", serviceName, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取实例详细信息
     */
    public InstanceDetails getInstanceDetails(String serviceName, String instanceId) {
        try {
            String instanceKey = registryKeys.getServiceInstanceKey(serviceName, instanceId);
            RMap<String, String> instanceMap = redissonClient.getMap(instanceKey, org.redisson.client.codec.StringCodec.INSTANCE);

            if (!instanceMap.isExists()) {
                return null;
            }

            Map<String, String> data = instanceMap.readAllMap();
            return parseInstanceDetails(serviceName, instanceId, data);

        } catch (Exception e) {
            logger.error("Failed to get instance details for {}:{}", serviceName, instanceId, e);
            return null;
        }
    }

    /**
     * 获取实例指标
     */
    public Map<String, Object> getInstanceMetrics(String serviceName, String instanceId) {
        try {
            InstanceDetails instance = getInstanceDetails(serviceName, instanceId);
            return instance != null ? instance.getMetrics() : Collections.emptyMap();
        } catch (Exception e) {
            logger.error("Failed to get instance metrics for {}:{}", serviceName, instanceId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 获取注册中心健康状态
     */
    public Map<String, Object> getRegistryHealth() {
        try {
            Map<String, Object> health = new HashMap<>();

            Set<String> services = getAllServices();
            health.put("totalServices", services.size());

            int totalInstances = 0;
            int healthyInstances = 0;
            int activeInstances = 0;

            for (String serviceName : services) {
                try {
                    List<InstanceDetails> instances = getActiveInstances(serviceName, Duration.ofMinutes(2));
                    totalInstances += instances.size();
                    healthyInstances += (int) instances.stream().filter(InstanceDetails::isHealthy).count();
                    activeInstances += instances.size(); // 这里的instances已经是活跃的
                } catch (Exception e) {
                    logger.warn("Failed to check health for service: {}", serviceName, e);
                }
            }

            health.put("totalInstances", totalInstances);
            health.put("healthyInstances", healthyInstances);
            health.put("activeInstances", activeInstances);
            health.put("healthyRate", totalInstances > 0 ? (double) healthyInstances / totalInstances * 100 : 0);
            health.put("timestamp", System.currentTimeMillis());

            return health;

        } catch (Exception e) {
            logger.error("Failed to get registry health", e);
            return Map.of(
                    "error", e.getMessage(),
                    "timestamp", System.currentTimeMillis()
            );
        }
    }

    /**
     * 手动清理过期实例
     */
    public Map<String, Integer> cleanupExpiredInstances(Duration timeout) {
        Map<String, Integer> result = new HashMap<>();

        try {
            Set<String> services = getAllServices();
            long currentTime = System.currentTimeMillis();
            long timeoutMs = timeout.toMillis();
            String keyPrefix = config.getKeyPrefix() != null ? config.getKeyPrefix() : "registry";

            for (String serviceName : services) {
                try {
                    String heartbeatKey = config.getRegistryKeys().getServiceHeartbeatsKey(serviceName);
                    List<String> expiredInstances = luaExecutor.executeCleanupExpiredInstances(
                            heartbeatKey, serviceName, currentTime, timeoutMs, keyPrefix
                    );

                    result.put(serviceName, expiredInstances.size());

                    if (!expiredInstances.isEmpty()) {
                        logger.info("Manually cleaned up {} expired instances for service: {}",
                                expiredInstances.size(), serviceName);
                    }

                } catch (Exception e) {
                    logger.error("Failed to cleanup expired instances for service: {}", serviceName, e);
                    result.put(serviceName, -1); // 表示清理失败
                }
            }

        } catch (Exception e) {
            logger.error("Failed to cleanup expired instances", e);
        }

        return result;
    }

    /**
     * 解析实例详情
     */
    private InstanceDetails parseInstanceDetails(String serviceName, String instanceId, Map<String, String> data) {
        try {
            InstanceDetails instance = new InstanceDetails(serviceName, instanceId);

            instance.setHost(data.get("host"));
            instance.setPort(parseIntSafely(data.get("port"), 0));
            instance.setProtocol(data.get("protocol"));
            instance.setEnabled(parseBooleanSafely(data.get("enabled"), true));
            instance.setHealthy(parseBooleanSafely(data.get("healthy"), true));
            instance.setWeight(parseIntSafely(data.get("weight"), 1));
            instance.setRegistrationTime(parseLongSafely(data.get("registrationTime"), 0));
            instance.setLastHeartbeatTime(parseLongSafely(data.get("lastHeartbeatTime"), 0));
            instance.setLastMetadataUpdate(parseLongSafely(data.get("lastMetadataUpdate"), 0));

            // 解析metadata
            String metadataStr = data.get("metadata");
            if (metadataStr != null && !metadataStr.isEmpty()) {
                try {
                    Map<String, Object> metadata = objectMapper.readValue(metadataStr, new TypeReference<>() {});
                    instance.setMetadata(metadata);
                } catch (Exception e) {
                    logger.warn("Failed to parse metadata for {}:{}", serviceName, instanceId, e);
                    instance.setMetadata(Collections.emptyMap());
                }
            }

            // 解析 metrics（独立 JSON 字段）
            Map<String, Object> metrics = Collections.emptyMap();
            String metricsStr = data.get("metrics");
            if (metricsStr != null && !metricsStr.isEmpty()) {
                try {
                    metrics = objectMapper.readValue(metricsStr, new TypeReference<>() {});
                } catch (Exception e) {
                    logger.warn("Failed to parse metrics for {}:{}", serviceName, instanceId, e);
                    metrics = Collections.emptyMap();
                }
            }
            instance.setMetrics(metrics);

            return instance;

        } catch (Exception e) {
            logger.error("Failed to parse instance details for {}:{}", serviceName, instanceId, e);
            return null;
        }
    }

    /**
     * 计算聚合指标
     */
    private Map<String, Object> calculateAggregatedMetrics(List<InstanceDetails> instances) {
        Map<String, Object> aggregated = new HashMap<>();

        if (instances.isEmpty()) {
            return aggregated;
        }

        // 计算平均心跳延迟
        double avgHeartbeatDelay = instances.stream()
                .mapToLong(InstanceDetails::getHeartbeatDelay)
                .average()
                .orElse(0);
        aggregated.put("avgHeartbeatDelay", avgHeartbeatDelay);

        // 聚合各种指标
        Map<String, List<Double>> numericMetrics = new HashMap<>();

        for (InstanceDetails instance : instances) {
            Map<String, Object> metrics = instance.getMetrics();
            if (metrics != null) {
                collectNumericMetrics(metrics, numericMetrics, "");
            }
        }

        // 计算平均值和统计信息
        for (Map.Entry<String, List<Double>> entry : numericMetrics.entrySet()) {
            String metricName = entry.getKey();
            List<Double> values = entry.getValue();

            if (!values.isEmpty()) {
                Map<String, Object> stats = new HashMap<>();
                stats.put("avg", values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
                stats.put("min", values.stream().mapToDouble(Double::doubleValue).min().orElse(0));
                stats.put("max", values.stream().mapToDouble(Double::doubleValue).max().orElse(0));
                stats.put("count", values.size());

                aggregated.put(metricName, stats);
            }
        }

        return aggregated;
    }

    /**
     * 收集数值型指标
     */
    private void collectNumericMetrics(Map<String, Object> metrics, Map<String, List<Double>> collector, String prefix) {
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Number) {
                collector.computeIfAbsent(key, k -> new ArrayList<>()).add(((Number) value).doubleValue());
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                collectNumericMetrics(nestedMap, collector, key);
            }
        }
    }

    // 安全解析方法
    private int parseIntSafely(String value, int defaultValue) {
        try {
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long parseLongSafely(String value, long defaultValue) {
        try {
            return value != null ? Long.parseLong(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean parseBooleanSafely(String value, boolean defaultValue) {
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    // Key生成方法统一走 RegistryKeys，避免与主实现不一致
}
