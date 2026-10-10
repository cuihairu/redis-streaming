package io.github.cuihairu.redis.streaming.mq.metrics;

/**
 * Pluggable collector for MQ internal metrics. Default is Noop.
 * Starter can provide a Micrometer-backed implementation and install it via MqMetrics.setCollector.
 *
 * <p>Tenant dimension (docs/Multi-Tenancy-Design.md): topic-scoped counters have
 * {@code (tenant, topic, partitionId)} overloads; producers/consumers call the
 * tenant-aware variant with their configured tenant. The defaults delegate to the
 * legacy signatures, so existing implementations keep compiling — override the
 * tenant-aware variants to expose the dimension.</p>
 */
public interface MqMetricsCollector {
    void incProduced(String topic, int partitionId);
    void incConsumed(String topic, int partitionId);
    void incAcked(String topic, int partitionId);
    void incRetried(String topic, int partitionId);
    void incDeadLetter(String topic, int partitionId);
    void recordHandleLatency(String topic, int partitionId, long millis);

    /** Tenant-aware produced counter (default: ignores the tenant). */
    default void incProduced(String tenant, String topic, int partitionId) { incProduced(topic, partitionId); }

    /** Tenant-aware consumed counter (default: ignores the tenant). */
    default void incConsumed(String tenant, String topic, int partitionId) { incConsumed(topic, partitionId); }

    /** Tenant-aware acked counter (default: ignores the tenant). */
    default void incAcked(String tenant, String topic, int partitionId) { incAcked(topic, partitionId); }

    /** Tenant-aware retried counter (default: ignores the tenant). */
    default void incRetried(String tenant, String topic, int partitionId) { incRetried(topic, partitionId); }

    /** Tenant-aware dead-letter counter (default: ignores the tenant). */
    default void incDeadLetter(String tenant, String topic, int partitionId) { incDeadLetter(topic, partitionId); }

    /** Tenant-aware handle latency (default: ignores the tenant). */
    default void recordHandleLatency(String tenant, String topic, int partitionId, long millis) {
        recordHandleLatency(topic, partitionId, millis);
    }

    /** Count of sends rejected by the {@link io.github.cuihairu.redis.streaming.mq.config.SendQuota}. */
    default void incRateLimited(String tenant, String topic) {}

    /** Optional: count messages that failed due to missing hash payload at parse time. */
    default void incPayloadMissing(String topic, int partitionId) {}

    /**
     * Optional: track in-flight message handling per consumer instance (useful for backpressure).
     *
     * @param consumerName consumer instance name
     * @param inFlight current in-flight count
     * @param maxInFlight configured max in-flight (0 means disabled)
     */
    default void setInFlight(String consumerName, long inFlight, int maxInFlight) {}

    /**
     * Optional: record the time spent waiting for an in-flight permit (backpressure wait).
     *
     * @param consumerName consumer instance name
     * @param waitMillis wait duration in milliseconds
     */
    default void recordBackpressureWait(String consumerName, long waitMillis) {}

    /**
     * Optional: track eligible partitions for this consumer (after applying partition pinning filters).
     */
    default void setEligiblePartitions(String consumerName, String topic, String consumerGroup, int eligibleCount) {}

    /**
     * Optional: track leased (actively running) partitions for this consumer.
     */
    default void setLeasedPartitions(String consumerName, String topic, String consumerGroup, int leasedCount) {}

    /**
     * Optional: track configured max leased partitions per consumer instance.
     */
    default void setMaxLeasedPartitions(String consumerName, int maxLeasedPartitions) {}

    /** Record a DLQ replay attempt. */
    default void recordDlqReplay(String topic, int partitionId, boolean success, long durationNanos) {}

    /** Increment count of deleted DLQ entries. */
    default void incDlqDelete(String topic) {}

    /** Increment count of cleared DLQ entries. */
    default void incDlqClear(String topic, long count) {}
}
