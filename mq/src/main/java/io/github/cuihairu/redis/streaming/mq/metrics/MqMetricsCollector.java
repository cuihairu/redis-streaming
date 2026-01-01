package io.github.cuihairu.redis.streaming.mq.metrics;

/**
 * Pluggable collector for MQ internal metrics. Default is Noop.
 * Starter can provide a Micrometer-backed implementation and install it via MqMetrics.setCollector.
 */
public interface MqMetricsCollector {
    void incProduced(String topic, int partitionId);
    void incConsumed(String topic, int partitionId);
    void incAcked(String topic, int partitionId);
    void incRetried(String topic, int partitionId);
    void incDeadLetter(String topic, int partitionId);
    void recordHandleLatency(String topic, int partitionId, long millis);

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
}
