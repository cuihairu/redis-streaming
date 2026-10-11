package io.github.cuihairu.redis.streaming.registry.heartbeat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heartbeat state manager
 *
 * <p>Tracks per-instance heartbeat and metadata state to decide whether a
 * heartbeat-only touch or a metadata write is due.</p>
 */
public class HeartbeatStateManager {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatStateManager.class);

    private final HeartbeatConfig config;
    private final Map<String, InstanceState> instanceStates = new ConcurrentHashMap<>();

    public HeartbeatStateManager(HeartbeatConfig config) {
        this.config = config != null ? config : new HeartbeatConfig();
    }

    /**
     * Instance state
     */
    private static class InstanceState {
        // Timestamps
        private long lastHeartbeatTime = 0;
        private long lastMetadataUpdateTime = 0;  // Last metadata update time

        // Content hash (for change detection)
        private int metadataHash = 0;             // Metadata content hash
    }

    /**
     * Decide whether a bare heartbeat touch is due: a heartbeat due on schedule must
     * fire even when nothing else changed, so the instance's Redis TTL and heartbeat
     * zset score stay alive.
     */
    public UpdateDecision shouldHeartbeatOnly(String serviceName, String instanceId) {
        long now = System.currentTimeMillis();
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.computeIfAbsent(stateKey, k -> new InstanceState());
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

    // ==================== Mark methods ====================

    /**
     * Mark metadata update completed
     */
    public void markMetadataUpdateCompleted(String serviceName, String instanceId,
                                            Map<String, Object> metadata) {
        String stateKey = buildStateKey(serviceName, instanceId);
        // computeIfAbsent, not get (B-33): registration marks the metadata hash before
        // any decision has run, so the entry does not exist yet — a plain get no-opped
        // and the first heartbeat misread the default hash 0 as a metadata change.
        InstanceState state = instanceStates.computeIfAbsent(stateKey, k -> new InstanceState());
        state.lastMetadataUpdateTime = System.currentTimeMillis();
        state.lastHeartbeatTime = state.lastMetadataUpdateTime;  // Update also counts as heartbeat
        state.metadataHash = calculateHash(metadata);
    }

    /**
     * Mark heartbeat-only update completed
     */
    public void markHeartbeatOnlyCompleted(String serviceName, String instanceId) {
        String stateKey = buildStateKey(serviceName, instanceId);
        InstanceState state = instanceStates.get(stateKey);
        if (state != null) {
            state.lastHeartbeatTime = System.currentTimeMillis();
        }
    }

    // ==================== Helper methods ====================

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
        info.put("metadataHash", state.metadataHash);
        return info;
    }

    /**
     * Build state key (serviceName:instanceId)
     * Avoids conflicts between the same instanceId across different services
     */
    private String buildStateKey(String serviceName, String instanceId) {
        return serviceName + ":" + instanceId;
    }
}
