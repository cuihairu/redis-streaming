package io.github.cuihairu.redis.streaming.registry.admin;

import lombok.Data;
import lombok.Getter;

import java.util.Map;

/**
 * Instance detailed information
 */
@Data
public class InstanceDetails {
    // Getters and Setters
    private String serviceName;
    private String instanceId;
    private String host;
    private int port;
    private String protocol;
    private boolean enabled;
    private boolean healthy;
    private int weight;
    private Map<String, Object> metadata;
    private long registrationTime;
    private long lastHeartbeatTime;
    private long lastMetadataUpdate;
    private Map<String, Object> metrics;

    public InstanceDetails(String serviceName, String instanceId) {
        this.serviceName = serviceName;
        this.instanceId = instanceId;
    }
    /**
     * Calculate heartbeat delay (milliseconds)
     */
    public long getHeartbeatDelay() {
        return System.currentTimeMillis() - lastHeartbeatTime;
    }

    /**
     * Check if the instance is expired
     */
    public boolean isExpired(long timeoutMs) {
        return getHeartbeatDelay() > timeoutMs;
    }
}