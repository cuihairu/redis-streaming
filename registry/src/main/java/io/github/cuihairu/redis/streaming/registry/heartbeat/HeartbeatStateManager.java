package io.github.cuihairu.redis.streaming.registry.heartbeat;

import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThreshold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heartbeat state manager
 *
 * <p>Distinguishes metadata and metrics state management:</p>
 * <ul>
 *   <li>metadata: Static business metadata, rarely changes</li>
 *   <li>metrics: Dynamic monitoring indicators, high-frequency changes</li>
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
     * Instance state (distinguishes metadata and metrics)
     */
    private static class InstanceState {
        // Timestamps
        private long lastHeartbeatTime = 0;
        private long lastMetadataUpdateTime = 0;  // Last metadata update time
        private long lastMetricsUpdateTime = 0;   // Last metrics update time

        // Content hash (for change detection)
        private int metadataHash = 0;             // Metadata content hash
        private int metricsHash = 0;              // Metrics content hash

        // Health status
        private Boolean lastHealthy = null;

        // Statistics
        private long consecutiveHeartbeatOnlyCount = 0;  // Consecutive heartbeat count (no data update)

        // Concurrency control
        private volatile boolean pendingHeartbeat = false;

        // Last committed metrics snapshot, used for threshold comparison
        private Map<String, Object> lastMetricsSnapshot = new HashMap<>();
        // Last committed metadata snapshot (reserved)
        private Map<String, Object> lastMetadataSnapshot = new HashMap<>();
    }

    // ==================== New separated methods ====================

    /**
     * Check if metrics need to be updated
     *
     * @param serviceName service name
     * @param instanceId instance ID
     * @param currentMetrics current metrics data
     * @return update decision
     */
    public UpdateDecision shouldUpdateMetrics(String serviceName, String instanceId,
                                              Map<String, Object> currentMetrics) {
        long now = System.currentTimeMillis();
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.computeIfAbsent(stateKey, k -> new InstanceState());

        // 1. Check time interval (avoid too frequent updates)
        long timeSinceLastUpdate = now - state.lastMetricsUpdateTime;
        if (timeSinceLastUpdate < config.getMetricsUpdateIntervalMs()) {
            // But still need to check if heartbeat is needed
            if (now - state.lastHeartbeatTime >= config.getHeartbeatInterval().toMillis()) {
                return UpdateDecision.HEARTBEAT_ONLY;
            }
            return UpdateDecision.NO_UPDATE;
        }

        // 2. Check if content has changed (using smart thresholds)
        int currentHash = calculateHash(currentMetrics);
        boolean hasSignificantChange = hasSignificantMetricsChange(
                state.metricsHash, currentHash, currentMetrics, state);

        if (hasSignificantChange) {
            logger.debug("Metrics update triggered for {}:{} by significant change",
                    serviceName, instanceId);
            return UpdateDecision.METRICS_UPDATE;
        }

        // 3. Force update check (prevent long periods without updates)
        if (state.consecutiveHeartbeatOnlyCount >= config.getForceUpdateThreshold()) {
            logger.debug("Metrics update triggered for {}:{} by force update threshold",
                    serviceName, instanceId);
            return UpdateDecision.METRICS_UPDATE;
        }

        // 4. Only heartbeat needed
        if (now - state.lastHeartbeatTime >= config.getHeartbeatInterval().toMillis()) {
            return UpdateDecision.HEARTBEAT_ONLY;
        }

        return UpdateDecision.NO_UPDATE;
    }

    /**
     * Check if metadata needs to be updated (rarely triggered)
     *
     * @param serviceName service name
     * @param instanceId instance ID
     * @param currentMetadata current metadata data
     * @return update decision
     */
    public UpdateDecision shouldUpdateMetadata(String serviceName, String instanceId,
                                               Map<String, Object> currentMetadata) {
        // If metadata change detection is disabled, return no update directly
        if (!config.isEnableMetadataChangeDetection()) {
            return UpdateDecision.NO_UPDATE;
        }

        long now = System.currentTimeMillis();
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.computeIfAbsent(stateKey, k -> new InstanceState());

        // 1. Check minimum update interval (metadata changes should be rare, needs longer interval)
        long timeSinceLastUpdate = now - state.lastMetadataUpdateTime;
        long intervalMs = config.getMetadataUpdateIntervalSeconds() * 1000L;
        if (intervalMs > 0 && timeSinceLastUpdate < intervalMs) {
            return UpdateDecision.NO_UPDATE;
        }

        // 2. Check if content has actually changed
        int currentHash = calculateHash(currentMetadata);
        if (currentHash != state.metadataHash) {
            return UpdateDecision.METADATA_UPDATE;
        }

        return UpdateDecision.NO_UPDATE;
    }

    // ==================== Backward compatible old methods ====================

    /**
     * Check if update is needed (backward compatible)
     *
     * @deprecated This method has confused semantics, it actually checks metrics changes.
     *             Use {@link #shouldUpdateMetrics(String, String, Map)} instead
     */
    @Deprecated
    public UpdateDecision shouldUpdate(String serviceName, String instanceId,
                                       Map<String, Object> currentMetadata, Boolean currentHealthy) {
        // Delegate to new method, but keep old behavior
        return shouldUpdateMetrics(serviceName, instanceId, currentMetadata);
    }

    // ==================== Mark methods ====================

    /**
     * Mark metrics update completed
     */
    public void markMetricsUpdateCompleted(String serviceName, String instanceId,
                                           Map<String, Object> metrics) {
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.get(stateKey);
        if (state != null) {
            state.lastMetricsUpdateTime = System.currentTimeMillis();
            state.lastHeartbeatTime = state.lastMetricsUpdateTime;  // Update also counts as heartbeat
            state.metricsHash = calculateHash(metrics);
            state.consecutiveHeartbeatOnlyCount = 0;
            state.pendingHeartbeat = false;
            // Store metrics snapshot for subsequent threshold judgment (shallow copy is sufficient)
            state.lastMetricsSnapshot = metrics != null ? new HashMap<>(metrics) : new HashMap<>();
        }
    }

    /**
     * Mark metadata update completed
     */
    public void markMetadataUpdateCompleted(String serviceName, String instanceId,
                                            Map<String, Object> metadata) {
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.get(stateKey);
        if (state != null) {
            state.lastMetadataUpdateTime = System.currentTimeMillis();
            state.lastHeartbeatTime = state.lastMetadataUpdateTime;  // Update also counts as heartbeat
            state.metadataHash = calculateHash(metadata);
            state.pendingHeartbeat = false;
            state.lastMetadataSnapshot = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        }
    }

    /**
     * Mark heartbeat-only update completed
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

    // ==================== Helper methods ====================

    /**
     * Check if metrics have significant changes (smart thresholds)
     */
    private boolean hasSignificantMetricsChange(int oldHash, int newHash,
                                                Map<String, Object> currentMetrics,
                                                InstanceState state) {
        // First collection
        if (oldHash == 0) {
            return true;
        }

        // No change at all
        if (oldHash == newHash) {
            return false;
        }

        // If no thresholds configured, hash change means update needed
        Map<String, ChangeThreshold> thresholds = config.getChangeThresholds();
        if (thresholds == null || thresholds.isEmpty()) {
            return true;
        }

        // Evaluate each item against thresholds to determine significant change
        for (Map.Entry<String, ChangeThreshold> entry : thresholds.entrySet()) {
            String path = entry.getKey();
            ChangeThreshold threshold = entry.getValue();

            Object oldVal = getNestedValue(state.lastMetricsSnapshot, path);
            Object newVal = getNestedValue(currentMetrics, path);

            if (oldVal == null && newVal == null) {
                continue;
            }

            if (threshold.isSignificant(oldVal, newVal)) {
                return true;
            }
        }

        // No items reached threshold, considered not significant
        return false;
    }

    /**
     * Calculate hash value of data
     */
    private int calculateHash(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return 0;
        }
        return Objects.hash(data);
    }

    /**
     * Remove instance state
     */
    public void removeInstanceState(String serviceName, String instanceId) {
        String stateKey = buildStateKey(serviceName, instanceId);
        instanceStates.remove(stateKey);
    }

    /**
     * Get instance state (for debugging)
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
     * Build state key (serviceName:instanceId)
     * Avoids conflicts between the same instanceId across different services
     */
    private String buildStateKey(String serviceName, String instanceId) {
        return serviceName + ":" + instanceId;
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
