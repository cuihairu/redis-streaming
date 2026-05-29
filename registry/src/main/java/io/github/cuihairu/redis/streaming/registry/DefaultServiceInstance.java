package io.github.cuihairu.redis.streaming.registry;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Default implementation of service instance
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultServiceInstance implements ServiceInstance {
    
    /**
     * Service name
     */
    private String serviceName;
    
    /**
     * Instance ID
     */
    private String instanceId;
    
    /**
     * Host address
     */
    private String host;
    
    /**
     * Port number
     */
    private int port;
    
    /**
     * Protocol
     */
    @Builder.Default
    private Protocol protocol = StandardProtocol.HTTP;
    
    /**
     * Whether enabled
     */
    @Builder.Default
    private boolean enabled = true;
    
    /**
     * Whether healthy
     */
    @Builder.Default
    private boolean healthy = true;
    
    /**
     * Weight
     */
    @Builder.Default
    private int weight = 1;
    
    /**
     * Metadata
     */
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();
    
    /**
     * Last heartbeat time
     */
    private LocalDateTime lastHeartbeatTime;
    
    /**
     * Registration time
     */
    @Builder.Default
    private LocalDateTime registrationTime = LocalDateTime.now();

    /**
     * Whether this is an ephemeral instance
     * true: ephemeral instance, relies on client heartbeats, automatically removed on timeout
     * false: persistent instance, server-side health checking, only marks unhealthy without removal
     */
    @Builder.Default
    private boolean ephemeral = true;

    @Override
    public int getPort() {
        return this.port > 0 ? this.port : getProtocol().getDefaultPort();
    }

    @Override
    public Protocol getProtocol() {
        return this.protocol != null ? this.protocol : StandardProtocol.HTTP;
    }
}