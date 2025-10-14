package io.github.cuihairu.redis.streaming.registry.event;

import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import java.util.Map;

/**
 * Strongly typed service change event payload for Pub/Sub.
 */
public class ServiceChangeEvent {
    private String serviceName;
    private ServiceChangeAction action;
    private String instanceId;
    private long timestamp;
    private InstanceSnapshot instance;

    public ServiceChangeEvent() {}

    public ServiceChangeEvent(String serviceName, ServiceChangeAction action, String instanceId, long timestamp, InstanceSnapshot instance) {
        this.serviceName = serviceName;
        this.action = action;
        this.instanceId = instanceId;
        this.timestamp = timestamp;
        this.instance = instance;
    }

    public String getServiceName() { return serviceName; }
    public ServiceChangeAction getAction() { return action; }
    public String getInstanceId() { return instanceId; }
    public long getTimestamp() { return timestamp; }
    public InstanceSnapshot getInstance() { return instance; }

    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public void setAction(ServiceChangeAction action) { this.action = action; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setInstance(InstanceSnapshot instance) { this.instance = instance; }

    public static class InstanceSnapshot {
        private String serviceName;
        private String instanceId;
        private String host;
        private int port;
        private String protocol; // name
        private boolean enabled;
        private boolean healthy;
        private int weight;
        private Map<String, String> metadata;

        public InstanceSnapshot() {}

        public String getServiceName() { return serviceName; }
        public String getInstanceId() { return instanceId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getProtocol() { return protocol; }
        public boolean isEnabled() { return enabled; }
        public boolean isHealthy() { return healthy; }
        public int getWeight() { return weight; }
        public Map<String, String> getMetadata() { return metadata; }

        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
        public void setHost(String host) { this.host = host; }
        public void setPort(int port) { this.port = port; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public void setWeight(int weight) { this.weight = weight; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    }
}

