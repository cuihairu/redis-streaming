package io.github.cuihairu.redis.streaming.registry.heartbeat;

/**
 * Update decision enum
 *
 * <p>Semantic definitions:</p>
 * <ul>
 *   <li>metadata: Static business metadata (region, version, etc.), set at registration, rarely changes</li>
 *   <li>metrics: Dynamic monitoring indicators (cpu, memory, qps, etc.), may change with each heartbeat</li>
 * </ul>
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
     * Update metrics + heartbeat time (high-frequency scenario, monitoring indicators change)
     */
    METRICS_UPDATE,

    /**
     * Update metadata + heartbeat time (low-frequency scenario, business configuration changes)
     */
    METADATA_UPDATE,

    /**
     * Update metadata + metrics + heartbeat time simultaneously (rare scenario, first registration or major change)
     */
    FULL_UPDATE
}