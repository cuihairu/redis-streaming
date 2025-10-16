package io.github.cuihairu.redis.streaming.mq.metrics;

/** Collector for stream/DLQ retention trimming metrics. */
public interface RetentionMetricsCollector {
    /** Record a trim attempt on main stream partitions. */
    void recordTrim(String topic, int partitionId, long deleted, String reason);
    /** Record a trim attempt on DLQ stream. */
    void recordDlqTrim(String topic, long deleted, String reason);
}

