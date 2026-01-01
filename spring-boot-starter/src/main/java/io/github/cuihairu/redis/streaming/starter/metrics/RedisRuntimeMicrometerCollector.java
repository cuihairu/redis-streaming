package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
}
