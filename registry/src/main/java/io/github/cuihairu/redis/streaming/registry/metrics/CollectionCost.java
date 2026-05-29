package io.github.cuihairu.redis.streaming.registry.metrics;

/**
 * Metric collection cost level
 */
public enum CollectionCost {
    /**
     * Low overhead, e.g., memory usage
     */
    LOW,

    /**
     * Medium overhead, e.g., disk I/O
     */
    MEDIUM,

    /**
     * High overhead, e.g., network latency testing
     */
    HIGH
}