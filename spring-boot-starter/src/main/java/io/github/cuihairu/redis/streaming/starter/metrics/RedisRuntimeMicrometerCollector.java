package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-backed Redis runtime metrics (per job/topic/group).
 */
public class RedisRuntimeMicrometerCollector implements RedisRuntimeMetricsCollector {

    private final MeterRegistry registry;
    private final Map<String, Counter> jobStarted = new ConcurrentHashMap<>();
    private final Map<String, Counter> jobCanceled = new ConcurrentHashMap<>();
    private final Map<String, Counter> pipelineStarted = new ConcurrentHashMap<>();
    private final Map<String, Counter> pipelineStartFailed = new ConcurrentHashMap<>();
    private final Map<String, Counter> handleSuccess = new ConcurrentHashMap<>();
    private final Map<String, Counter> handleError = new ConcurrentHashMap<>();
    private final Map<String, Timer> handleLatency = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> keyedStateSizeFields = new ConcurrentHashMap<>();
    private final Map<String, Counter> keyedStateHotKey = new ConcurrentHashMap<>();
    private final Map<String, Counter> keyedStateRead = new ConcurrentHashMap<>();
    private final Map<String, Counter> keyedStateWrite = new ConcurrentHashMap<>();
    private final Map<String, Counter> keyedStateDelete = new ConcurrentHashMap<>();
    private final Map<String, Timer> keyedStateReadLatency = new ConcurrentHashMap<>();
    private final Map<String, Timer> keyedStateWriteLatency = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final Map<String, Counter> checkpointTriggered = new ConcurrentHashMap<>();
    private final Map<String, Counter> checkpointCompleted = new ConcurrentHashMap<>();
    private final Map<String, Counter> checkpointFailed = new ConcurrentHashMap<>();
    private final Map<String, Timer> checkpointDuration = new ConcurrentHashMap<>();
    private final Map<String, Timer> checkpointDrainDuration = new ConcurrentHashMap<>();
    private final Map<String, Timer> checkpointStoreDuration = new ConcurrentHashMap<>();
    private final Map<String, Timer> checkpointSinkCommitDuration = new ConcurrentHashMap<>();
    private final Map<String, Counter> windowLateDropped = new ConcurrentHashMap<>();
    private final Map<String, Counter> windowFired = new ConcurrentHashMap<>();

    public RedisRuntimeMicrometerCollector(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void incJobStarted(String jobName) {
        counter(jobStarted, "redis_streaming_runtime_job_started_total", jobName, null, null).increment();
    }

    @Override
    public void incJobCanceled(String jobName) {
        counter(jobCanceled, "redis_streaming_runtime_job_canceled_total", jobName, null, null).increment();
    }

    @Override
    public void incCheckpointTriggered(String jobName) {
        counter(checkpointTriggered, "redis_streaming_runtime_checkpoint_triggered_total", jobName, null, null).increment();
    }

    @Override
    public void incCheckpointCompleted(String jobName) {
        counter(checkpointCompleted, "redis_streaming_runtime_checkpoint_completed_total", jobName, null, null).increment();
    }

    @Override
    public void incCheckpointFailed(String jobName) {
        counter(checkpointFailed, "redis_streaming_runtime_checkpoint_failed_total", jobName, null, null).increment();
    }

    @Override
    public void recordCheckpointDuration(String jobName, long millis) {
        timer(checkpointDuration, "redis_streaming_runtime_checkpoint_duration_ms", jobName, null, null)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordCheckpointDrainDuration(String jobName, long millis) {
        timer(checkpointDrainDuration, "redis_streaming_runtime_checkpoint_drain_duration_ms", jobName, null, null)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordCheckpointStoreDuration(String jobName, long millis) {
        timer(checkpointStoreDuration, "redis_streaming_runtime_checkpoint_store_duration_ms", jobName, null, null)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordCheckpointSinkCommitDuration(String jobName, long millis) {
        timer(checkpointSinkCommitDuration, "redis_streaming_runtime_checkpoint_sink_commit_duration_ms", jobName, null, null)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void incPipelineStarted(String jobName, String topic, String consumerGroup) {
        counter(pipelineStarted, "redis_streaming_runtime_pipeline_started_total", jobName, topic, consumerGroup).increment();
    }

    @Override
    public void incPipelineStartFailed(String jobName, String topic, String consumerGroup) {
        counter(pipelineStartFailed, "redis_streaming_runtime_pipeline_start_failed_total", jobName, topic, consumerGroup).increment();
    }

    @Override
    public void incHandleSuccess(String jobName, String topic, String consumerGroup) {
        counter(handleSuccess, "redis_streaming_runtime_handle_success_total", jobName, topic, consumerGroup).increment();
    }

    @Override
    public void incHandleError(String jobName, String topic, String consumerGroup) {
        counter(handleError, "redis_streaming_runtime_handle_error_total", jobName, topic, consumerGroup).increment();
    }

    @Override
    public void recordHandleLatency(String jobName, String topic, String consumerGroup, long millis) {
        timer(handleLatency, "redis_streaming_runtime_handle_latency_ms", jobName, topic, consumerGroup)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordKeyedStateSize(String jobName,
                                     String topic,
                                     String consumerGroup,
                                     String operatorId,
                                     String stateName,
                                     int partitionId,
                                     long fields) {
        if (fields < 0) {
            return;
        }
        keyedStateSizeSummary("redis_streaming_runtime_keyed_state_size_fields",
                jobName, topic, consumerGroup, operatorId, stateName, partitionId)
                .record(fields);
    }

    @Override
    public void incKeyedStateHotKey(String jobName,
                                    String topic,
                                    String consumerGroup,
                                    String operatorId,
                                    String stateName,
                                    int partitionId,
                                    long fields) {
        counter(keyedStateHotKey, "redis_streaming_runtime_keyed_state_hot_key_total",
                jobName, topic, consumerGroup, operatorId, stateName, partitionId)
                .increment();
    }

    @Override
    public void incKeyedStateRead(String jobName, String topic, String consumerGroup, String operatorId, String stateName, int partitionId) {
        counter(keyedStateRead, "redis_streaming_runtime_keyed_state_read_total",
                jobName, topic, consumerGroup, operatorId, stateName, partitionId).increment();
    }

    @Override
    public void incKeyedStateWrite(String jobName, String topic, String consumerGroup, String operatorId, String stateName, int partitionId) {
        counter(keyedStateWrite, "redis_streaming_runtime_keyed_state_write_total",
                jobName, topic, consumerGroup, operatorId, stateName, partitionId).increment();
    }

    @Override
    public void incKeyedStateDelete(String jobName, String topic, String consumerGroup, String operatorId, String stateName, int partitionId) {
        counter(keyedStateDelete, "redis_streaming_runtime_keyed_state_delete_total",
                jobName, topic, consumerGroup, operatorId, stateName, partitionId).increment();
    }

    @Override
    public void recordKeyedStateReadLatency(String jobName, String topic, String consumerGroup, String operatorId, String stateName, int partitionId, long millis) {
        if (millis < 0) {
            return;
        }
        timer(keyedStateReadLatency, "redis_streaming_runtime_keyed_state_read_latency_ms",
                jobName, topic, consumerGroup, operatorId, stateName, partitionId)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordKeyedStateWriteLatency(String jobName, String topic, String consumerGroup, String operatorId, String stateName, int partitionId, long millis) {
        if (millis < 0) {
            return;
        }
        timer(keyedStateWriteLatency, "redis_streaming_runtime_keyed_state_write_latency_ms",
                jobName, topic, consumerGroup, operatorId, stateName, partitionId)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void setEventTimeTimerQueueSize(String jobName, String topic, String consumerGroup, int size) {
        gauge("redis_streaming_runtime_event_time_timer_queue_size",
                "job", jobName,
                "topic", topic,
                "group", consumerGroup).set(Math.max(0L, size));
    }

    @Override
    public void setWatermarkMs(String jobName, String topic, String consumerGroup, long watermarkMs) {
        gauge("redis_streaming_runtime_watermark_ms",
                "job", jobName,
                "topic", topic,
                "group", consumerGroup).set(watermarkMs);
    }

    @Override
    public void incWindowLateDropped(String jobName, String topic, String consumerGroup, String operatorId, String windowName, int partitionId) {
        counter(windowLateDropped, "redis_streaming_runtime_window_late_dropped_total",
                jobName, topic, consumerGroup, operatorId, windowName, partitionId).increment();
    }

    @Override
    public void incWindowFired(String jobName, String topic, String consumerGroup, String operatorId, String windowName, int partitionId) {
        counter(windowFired, "redis_streaming_runtime_window_fired_total",
                jobName, topic, consumerGroup, operatorId, windowName, partitionId).increment();
    }

    private Counter counter(Map<String, Counter> cache, String name, String job, String topic, String group) {
        String key = name + "|" + job + "|" + (topic == null ? "" : topic) + "|" + (group == null ? "" : group);
        return cache.computeIfAbsent(key, k -> {
            Counter.Builder b = Counter.builder(name).tag("job", job);
            if (topic != null) b.tag("topic", topic);
            if (group != null) b.tag("group", group);
            return b.register(registry);
        });
    }

    private Counter counter(Map<String, Counter> cache,
                            String name,
                            String job,
                            String topic,
                            String group,
                            String operatorId,
                            String stateName,
                            int partitionId) {
        String key = name + "|" + job + "|" + topic + "|" + group + "|" + operatorId + "|" + stateName + "|" + partitionId;
        return cache.computeIfAbsent(key, k -> Counter.builder(name)
                .tag("job", job)
                .tag("topic", topic)
                .tag("group", group)
                .tag("operator", operatorId)
                .tag("state", stateName)
                .tag("partition", String.valueOf(partitionId))
                .register(registry));
    }

    private Timer timer(Map<String, Timer> cache, String name, String job, String topic, String group) {
        String key = name + "|" + job + "|" + (topic == null ? "" : topic) + "|" + (group == null ? "" : group);
        return cache.computeIfAbsent(key, k -> {
            Timer.Builder b = Timer.builder(name).tag("job", job);
            if (topic != null) b.tag("topic", topic);
            if (group != null) b.tag("group", group);
            return b.register(registry);
        });
    }

    private DistributionSummary keyedStateSizeSummary(String name,
                                                      String job,
                                                      String topic,
                                                      String group,
                                                      String operatorId,
                                                      String stateName,
                                                      int partitionId) {
        String key = name + "|" + job + "|" + topic + "|" + group + "|" + operatorId + "|" + stateName + "|" + partitionId;
        return keyedStateSizeFields.computeIfAbsent(key, k -> DistributionSummary.builder(name)
                .tag("job", job)
                .tag("topic", topic)
                .tag("group", group)
                .tag("operator", operatorId)
                .tag("state", stateName)
                .tag("partition", String.valueOf(partitionId))
                .register(registry));
    }

    private Timer timer(Map<String, Timer> cache,
                        String name,
                        String job,
                        String topic,
                        String group,
                        String operatorId,
                        String stateName,
                        int partitionId) {
        String key = name + "|" + job + "|" + topic + "|" + group + "|" + operatorId + "|" + stateName + "|" + partitionId;
        return cache.computeIfAbsent(key, k -> Timer.builder(name)
                .tag("job", job)
                .tag("topic", topic)
                .tag("group", group)
                .tag("operator", operatorId)
                .tag("state", stateName)
                .tag("partition", String.valueOf(partitionId))
                .register(registry));
    }

    private AtomicLong gauge(String name, String tagK1, String tagV1, String tagK2, String tagV2, String tagK3, String tagV3) {
        String key = name + "|" + tagK1 + "|" + tagV1 + "|" + tagK2 + "|" + tagV2 + "|" + tagK3 + "|" + tagV3;
        return gauges.computeIfAbsent(key, k -> {
            AtomicLong holder = new AtomicLong(0L);
            Gauge.builder(name, holder, v -> (double) v.get())
                    .tag(tagK1, tagV1)
                    .tag(tagK2, tagV2)
                    .tag(tagK3, tagV3)
                    .register(registry);
            return holder;
        });
    }
}
