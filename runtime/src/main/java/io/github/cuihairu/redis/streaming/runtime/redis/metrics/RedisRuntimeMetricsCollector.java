package io.github.cuihairu.redis.streaming.runtime.redis.metrics;

/**
 * Pluggable collector for Redis runtime internal metrics. Default is Noop.
 *
 * <p>Intended for runtime-level operational visibility (job lifecycle, pipeline startup, handler outcomes).</p>
 */
public interface RedisRuntimeMetricsCollector {

    void incJobStarted(String jobName);

    void incJobCanceled(String jobName);

    void incPipelineStarted(String jobName, String topic, String consumerGroup);

    void incPipelineStartFailed(String jobName, String topic, String consumerGroup);

    void incHandleSuccess(String jobName, String topic, String consumerGroup);

    void incHandleError(String jobName, String topic, String consumerGroup);

    void recordHandleLatency(String jobName, String topic, String consumerGroup, long millis);

    void recordKeyedStateSize(String jobName,
                              String topic,
                              String consumerGroup,
                              String operatorId,
                              String stateName,
                              int partitionId,
                              long fields);

    default void incKeyedStateHotKey(String jobName,
                                     String topic,
                                     String consumerGroup,
                                     String operatorId,
                                     String stateName,
                                     int partitionId,
                                     long fields) {
        // default no-op for backward compatibility
    }
}
