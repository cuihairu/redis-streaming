package io.github.cuihairu.redis.streaming.registry;

/**
 * Protocol interface
 * Defines the protocol specification supported by service instances
 */
public interface Protocol {
    /**
     * Get the protocol name
     */
    String getName();
    
    /**
     * Whether to use a secure connection
     */
    boolean isSecure();
    
    /**
     * Get the default port
     */
    int getDefaultPort();
    
    /**
     * Get the protocol description
     */
    default String getDescription() {
        return getName();
    }
}