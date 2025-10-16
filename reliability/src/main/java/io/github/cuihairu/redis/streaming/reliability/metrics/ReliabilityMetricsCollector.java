package io.github.cuihairu.redis.streaming.reliability.metrics;

/**
 * Collector interface for reliability (DLQ) metrics.
 */
public interface ReliabilityMetricsCollector {
    /**
     * Record a DLQ replay attempt.
     * @param topic topic name
     * @param partitionId target partition id
     * @param success whether the replay succeeded
     * @param durationNanos time spent publishing
     */
    void recordDlqReplay(String topic, int partitionId, boolean success, long durationNanos);

    /** Increment count of deleted DLQ entries (explicit delete/drop). */
    default void incDlqDelete(String topic) {}

    /** Increment count of cleared DLQ entries. */
    default void incDlqClear(String topic, long count) {}
}

