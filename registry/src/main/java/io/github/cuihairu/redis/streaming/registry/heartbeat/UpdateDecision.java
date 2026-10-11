package io.github.cuihairu.redis.streaming.registry.heartbeat;

/**
 * Update decision enum for heartbeat processing
 */
public enum UpdateDecision {
    /**
     * No update needed (completely skip, do not even update heartbeat time)
     */
    NO_UPDATE,

    /**
     * Only update heartbeat timestamp (most common scenario, no data changes)
     */
    HEARTBEAT_ONLY,

    /**
     * Update metadata + heartbeat time (low-frequency scenario, business configuration changes)
     */
    METADATA_UPDATE
}
