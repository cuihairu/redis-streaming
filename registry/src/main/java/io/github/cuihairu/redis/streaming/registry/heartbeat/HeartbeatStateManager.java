package io.github.cuihairu.redis.streaming.registry.heartbeat;

import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThreshold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 心跳状态管理器
 *
 * <p>区分 metadata 和 metrics 的状态管理：</p>
 * <ul>
 *   <li>metadata: 静态业务元数据，几乎不变</li>
 *   <li>metrics: 动态监控指标，高频变化</li>
 * </ul>
 */
public class HeartbeatStateManager {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatStateManager.class);

    private final HeartbeatConfig config;
    private final Map<String, InstanceState> instanceStates = new ConcurrentHashMap<>();

    public HeartbeatStateManager(HeartbeatConfig config) {
        this.config = config != null ? config : new HeartbeatConfig();
    }

    /**
     * 实例状态（区分 metadata 和 metrics）
     */
    private static class InstanceState {
        // 时间戳
        private long lastHeartbeatTime = 0;
        private long lastMetadataUpdateTime = 0;  // metadata 最后更新时间
        private long lastMetricsUpdateTime = 0;   // metrics 最后更新时间

        // 内容哈希（用于变化检测）
        private int metadataHash = 0;             // metadata 内容哈希
        private int metricsHash = 0;              // metrics 内容哈希

        // 健康状态
        private Boolean lastHealthy = null;

        // 统计信息
        private long consecutiveHeartbeatOnlyCount = 0;  // 连续心跳次数（未更新数据）

        // 并发控制
        private volatile boolean pendingHeartbeat = false;
    }

    // ==================== 新的分离方法 ====================

    /**
     * 检查是否需要更新 metrics
     *
     * @param serviceName 服务名
     * @param instanceId 实例ID
     * @param currentMetrics 当前 metrics 数据
     * @return 更新决策
     */
    public UpdateDecision shouldUpdateMetrics(String serviceName, String instanceId,
                                              Map<String, Object> currentMetrics) {
        long now = System.currentTimeMillis();
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.computeIfAbsent(stateKey, k -> new InstanceState());

        // 1. 检查时间间隔（避免更新过于频繁）
        long timeSinceLastUpdate = now - state.lastMetricsUpdateTime;
        if (timeSinceLastUpdate < config.getMetricsUpdateIntervalMs()) {
            // 但仍需检查是否需要心跳
            if (now - state.lastHeartbeatTime >= config.getHeartbeatInterval().toMillis()) {
                return UpdateDecision.HEARTBEAT_ONLY;
            }
            return UpdateDecision.NO_UPDATE;
        }

        // 2. 检查内容是否变化（使用智能阈值）
        int currentHash = calculateHash(currentMetrics);
        boolean hasSignificantChange = hasSignificantMetricsChange(
                state.metricsHash, currentHash, currentMetrics, state);

        if (hasSignificantChange) {
            logger.debug("Metrics update triggered for {}:{} by significant change",
                    serviceName, instanceId);
            return UpdateDecision.METRICS_UPDATE;
        }

        // 3. 强制更新检查（防止长时间不更新）
        if (state.consecutiveHeartbeatOnlyCount >= config.getForceUpdateThreshold()) {
            logger.debug("Metrics update triggered for {}:{} by force update threshold",
                    serviceName, instanceId);
            return UpdateDecision.METRICS_UPDATE;
        }

        // 4. 只需要心跳
        if (now - state.lastHeartbeatTime >= config.getHeartbeatInterval().toMillis()) {
            return UpdateDecision.HEARTBEAT_ONLY;
        }

        return UpdateDecision.NO_UPDATE;
    }

    /**
     * 检查是否需要更新 metadata（极少触发）
     *
     * @param serviceName 服务名
     * @param instanceId 实例ID
     * @param currentMetadata 当前 metadata 数据
     * @return 更新决策
     */
    public UpdateDecision shouldUpdateMetadata(String serviceName, String instanceId,
                                               Map<String, Object> currentMetadata) {
        // 如果禁用了 metadata 变化检测，直接返回不更新
        if (!config.isEnableMetadataChangeDetection()) {
            return UpdateDecision.NO_UPDATE;
        }

        long now = System.currentTimeMillis();
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.computeIfAbsent(stateKey, k -> new InstanceState());

        // 1. 检查最小更新间隔（metadata 变化应该很少，需要更长的间隔）
        long timeSinceLastUpdate = now - state.lastMetadataUpdateTime;
        if (timeSinceLastUpdate < config.getMetadataUpdateIntervalSeconds() * 1000L) {
            return UpdateDecision.NO_UPDATE;
        }

        // 2. 检查内容是否确实变化
        int currentHash = calculateHash(currentMetadata);
        if (currentHash != state.metadataHash) {
            logger.info("Metadata changed for instance {}:{}, will update",
                    serviceName, instanceId);
            return UpdateDecision.METADATA_UPDATE;
        }

        return UpdateDecision.NO_UPDATE;
    }

    // ==================== 向后兼容的旧方法 ====================

    /**
     * 检查是否需要更新（保持向后兼容）
     *
     * @deprecated 此方法语义混乱，实际检查的是 metrics 变化。
     *             请使用 {@link #shouldUpdateMetrics(String, String, Map)} 替代
     */
    @Deprecated
    public UpdateDecision shouldUpdate(String serviceName, String instanceId,
                                       Map<String, Object> currentMetadata, Boolean currentHealthy) {
        // 委托给新方法，但保持旧的行为
        return shouldUpdateMetrics(serviceName, instanceId, currentMetadata);
    }

    // ==================== 标记方法 ====================

    /**
     * 标记 metrics 更新完成
     */
    public void markMetricsUpdateCompleted(String serviceName, String instanceId,
                                           Map<String, Object> metrics) {
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.get(stateKey);
        if (state != null) {
            state.lastMetricsUpdateTime = System.currentTimeMillis();
            state.lastHeartbeatTime = state.lastMetricsUpdateTime;  // 更新时也算心跳
            state.metricsHash = calculateHash(metrics);
            state.consecutiveHeartbeatOnlyCount = 0;
            state.pendingHeartbeat = false;
        }
    }

    /**
     * 标记 metadata 更新完成
     */
    public void markMetadataUpdateCompleted(String serviceName, String instanceId,
                                            Map<String, Object> metadata) {
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.get(stateKey);
        if (state != null) {
            state.lastMetadataUpdateTime = System.currentTimeMillis();
            state.lastHeartbeatTime = state.lastMetadataUpdateTime;  // 更新时也算心跳
            state.metadataHash = calculateHash(metadata);
            state.pendingHeartbeat = false;
        }
    }

    /**
     * 标记仅心跳更新完成
     */
    public void markHeartbeatOnlyCompleted(String serviceName, String instanceId) {
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.get(stateKey);
        if (state != null) {
            state.lastHeartbeatTime = System.currentTimeMillis();
            state.consecutiveHeartbeatOnlyCount++;
            state.pendingHeartbeat = false;
        }
    }

    // ==================== 旧的标记方法（向后兼容）====================

    /**
     * 标记操作开始，避免重复更新
     *
     * @deprecated 使用具体的标记方法替代
     */
    @Deprecated
    public void markUpdatePending(String serviceName, String instanceId, UpdateDecision decision) {
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.get(stateKey);
        if (state != null && decision == UpdateDecision.HEARTBEAT_ONLY) {
            state.pendingHeartbeat = true;
        }
    }

    /**
     * 操作完成后清除标记
     *
     * @deprecated 使用具体的 markXxxCompleted() 方法替代
     */
    @Deprecated
    public void markUpdateCompleted(String serviceName, String instanceId, UpdateDecision decision) {
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.get(stateKey);
        if (state != null) {
            state.pendingHeartbeat = false;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查 metrics 是否有显著变化（智能阈值）
     */
    private boolean hasSignificantMetricsChange(int oldHash, int newHash,
                                                Map<String, Object> currentMetrics,
                                                InstanceState state) {
        if (oldHash == 0) {
            return true;  // 首次收集 metrics
        }

        if (oldHash != newHash) {
            // Hash 不同，进一步检查具体字段的变化幅度
            // 可以在这里实现更精细的阈值检查
            return true;
        }

        return false;
    }

    /**
     * 计算数据的哈希值
     */
    private int calculateHash(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return 0;
        }
        return Objects.hash(data);
    }

    /**
     * 移除实例状态
     */
    public void removeInstanceState(String serviceName, String instanceId) {
        String stateKey = buildStateKey(serviceName, instanceId);
        instanceStates.remove(stateKey);
    }

    /**
     * 获取实例状态（用于调试）
     */
    public Map<String, Object> getInstanceStateInfo(String serviceName, String instanceId) {
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.get(stateKey);
        if (state == null) {
            return null;
        }

        Map<String, Object> info = new HashMap<>();
        info.put("lastHeartbeatTime", state.lastHeartbeatTime);
        info.put("lastMetadataUpdateTime", state.lastMetadataUpdateTime);
        info.put("lastMetricsUpdateTime", state.lastMetricsUpdateTime);
        info.put("metadataHash", state.metadataHash);
        info.put("metricsHash", state.metricsHash);
        info.put("consecutiveHeartbeatOnlyCount", state.consecutiveHeartbeatOnlyCount);
        info.put("pendingHeartbeat", state.pendingHeartbeat);
        info.put("lastHealthy", state.lastHealthy);
        return info;
    }

    /**
     * 构建状态 Key（serviceName:instanceId）
     * 避免不同服务的相同 instanceId 冲突
     */
    private String buildStateKey(String serviceName, String instanceId) {
        return serviceName + ":" + instanceId;
    }

    // ==================== 以下是原有但未使用的方法，保留以防需要 ====================

    private boolean shouldUpdateMetadataOld(InstanceState state, Map<String, Object> currentMetadata,
                                            Boolean currentHealthy, long now) {
        // 1. 定时metadata更新
        if (now - state.lastMetadataUpdateTime >= config.getMetadataInterval().toMillis()) {
            logger.debug("Metadata update triggered by interval");
            return true;
        }

        // 2. 健康状态变化
        if (config.isForceUpdateOnHealthChange() && !Objects.equals(currentHealthy, state.lastHealthy)) {
            logger.debug("Metadata update triggered by health change: {} -> {}", state.lastHealthy, currentHealthy);
            return true;
        }

        // 3. 关键指标变化超过阈值
        if (hasSignificantMetadataChangeOld(state.metricsHash, currentMetadata)) {
            logger.debug("Metadata update triggered by significant change");
            return true;
        }

        return false;
    }

    private boolean hasSignificantMetadataChangeOld(int previousHash, Map<String, Object> currentMetadata) {
        if (previousHash == 0) {
            return !currentMetadata.isEmpty(); // 首次设置metadata
        }

        for (Map.Entry<String, ChangeThreshold> entry : config.getChangeThresholds().entrySet()) {
            String key = entry.getKey();
            ChangeThreshold threshold = entry.getValue();

            Object currentValue = getNestedValue(currentMetadata, key);
            // 注意：这里缺少 previousValue 的获取，原实现有问题
            // Object previousValue = getNestedValue(previousMetadata, key);

            // 暂时简化处理
            if (currentValue != null) {
                return true;
            }
        }

        return false;
    }

    private Object getNestedValue(Map<String, Object> map, String path) {
        String[] parts = path.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                return null;
            }
        }

        return current;
    }
}
