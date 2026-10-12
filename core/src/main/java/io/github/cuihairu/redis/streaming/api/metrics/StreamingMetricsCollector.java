package io.github.cuihairu.redis.streaming.api.metrics;

/**
 * Public SPI for streaming runtime metrics (Metrics-Unification-Design v2): implement this to
 * receive the runtime's operational visibility points (job lifecycle, pipeline startup, handler
 * outcomes, checkpoints, keyed state, watermarks, windows). The default implementation is Noop;
 * spring-boot-starter bridges it to Micrometer when a {@code MeterRegistry} bean exists.
 */
public interface StreamingMetricsCollector {

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

    /** Checkpoint lifecycle: triggered. */
    default void incCheckpointTriggered(String jobName) {
    }

    /** Checkpoint lifecycle: completed successfully (including sink commit). */
    default void incCheckpointCompleted(String jobName) {
    }

    /** Checkpoint lifecycle: failed (store or sink commit). */
    default void incCheckpointFailed(String jobName) {
    }

    /** Overall checkpoint duration (pause+drain+store+sink commit+ack). */
    default void recordCheckpointDuration(String jobName, long millis) {
    }

    /** Drain duration: pause -> inFlight drained (or timed out). */
    default void recordCheckpointDrainDuration(String jobName, long millis) {
    }

    /** Time to store checkpoint snapshot into Redis. */
    default void recordCheckpointStoreDuration(String jobName, long millis) {
    }

    /** Time spent in sink onCheckpointComplete (and sinkCommitted marker). */
    default void recordCheckpointSinkCommitDuration(String jobName, long millis) {
    }

    /** Keyed state: read operation count. */
    default void incKeyedStateRead(String jobName,
                                   String topic,
                                   String consumerGroup,
                                   String operatorId,
                                   String stateName,
                                   int partitionId) {
    }

    /** Keyed state: write/update operation count. */
    default void incKeyedStateWrite(String jobName,
                                    String topic,
                                    String consumerGroup,
                                    String operatorId,
                                    String stateName,
                                    int partitionId) {
    }

    /** Keyed state: delete/clear operation count. */
    default void incKeyedStateDelete(String jobName,
                                     String topic,
                                     String consumerGroup,
                                     String operatorId,
                                     String stateName,
                                     int partitionId) {
    }

    /** Keyed state: read latency. */
    default void recordKeyedStateReadLatency(String jobName,
                                             String topic,
                                             String consumerGroup,
                                             String operatorId,
                                             String stateName,
                                             int partitionId,
                                             long millis) {
    }

    /** Keyed state: write latency. */
    default void recordKeyedStateWriteLatency(String jobName,
                                              String topic,
                                              String consumerGroup,
                                              String operatorId,
                                              String stateName,
                                              int partitionId,
                                              long millis) {
    }

    /** Event-time timer queue size (in-memory), per pipeline runner. */
    default void setEventTimeTimerQueueSize(String jobName,
                                            String topic,
                                            String consumerGroup,
                                            int size) {
    }

    /** Current event-time watermark (best-effort). */
    default void setWatermarkMs(String jobName,
                                String topic,
                                String consumerGroup,
                                long watermarkMs) {
    }

    /** Window: dropped element due to lateness (watermark beyond window close time). */
    default void incWindowLateDropped(String jobName,
                                      String topic,
                                      String consumerGroup,
                                      String operatorId,
                                      String windowName,
                                      int partitionId) {
    }

    /** Window: fired one window result (final fire). */
    default void incWindowFired(String jobName,
                                String topic,
                                String consumerGroup,
                                String operatorId,
                                String windowName,
                                int partitionId) {
    }

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
