package io.github.cuihairu.redis.streaming.registry;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Service instance information interface
 * Extends ServiceIdentity, contains complete information about a service instance
 */
public interface ServiceInstance extends ServiceIdentity {
    /**
     * Get the host address
     */
    String getHost();
    
    /**
     * Get the port number
     */
    default int getPort(){
        return getProtocol().getDefaultPort();
    }
    
    /**
     * Get the metadata
     */
    Map<String, String> getMetadata();
    
    /**
     * Whether the instance is enabled
     */
    boolean isEnabled();
    
    /**
     * Whether the instance is healthy
     */
    boolean isHealthy();
    
    /**
     * Whether to use a secure connection (HTTPS)
     */
    default boolean isSecure() {
        return getProtocol().isSecure();
    }
    
    /**
     * Get the protocol information
     */
    default Protocol getProtocol() {
        return StandardProtocol.HTTP;
    }
    
    /**
     * Get the service URI
     */
    default URI getUri() {
        try {
            Protocol protocol = getProtocol();
            return new URI(protocol.getName(), null, getHost(), getPort(), null, null, null);
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("Invalid service instance URI", e);
        }
    }
    
    /**
     * Get the protocol scheme
     */
    default String getScheme() {
        return getProtocol().getName();
    }
    
    /**
     * Get the weight (used for load balancing)
     */
    default int getWeight() {
        return 1;
    }
    
    /**
     * Get the last heartbeat time
     */
    default LocalDateTime getLastHeartbeatTime() {
        return null;
    }
    
    /**
     * Set the last heartbeat time
     */
    default void setLastHeartbeatTime(LocalDateTime time) {
        // Default empty implementation; concrete classes can override
    }
    
    /**
     * Get the registration time
     */
    default LocalDateTime getRegistrationTime() {
        return null;
    }

    /**
     * Whether this is an ephemeral instance
     * <p>
     * Ephemeral instance (ephemeral=true):
     * - Relies on client heartbeats to stay alive
     * - Automatically removed after heartbeat timeout
     * - Suitable for dynamic microservices, containerized applications
     * - Default: marked unhealthy after 15 seconds without heartbeat, removed after 30 seconds
     * <p>
     * Persistent instance (ephemeral=false):
     * - Server-side active health checking
     * - Only marked unhealthy on failure, not removed
     * - Requires manual deregistration to be removed
     * - Suitable for databases, DNS, and other fixed services
     *
     * @return true for ephemeral instance (default), false for persistent instance
     */
    default boolean isEphemeral() {
        return true;  // Default to ephemeral instance, consistent with Nacos
    }
}