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
}
