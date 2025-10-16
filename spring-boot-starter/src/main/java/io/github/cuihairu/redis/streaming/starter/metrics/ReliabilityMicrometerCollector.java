package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-backed metrics for reliability (DLQ) operations.
 */
public class ReliabilityMicrometerCollector implements ReliabilityMetricsCollector {

    private final MeterRegistry registry;
    private final Map<String, Counter> replaySuccess = new ConcurrentHashMap<>();
    private final Map<String, Counter> replayFailure = new ConcurrentHashMap<>();
    private final Map<String, Timer> replayLatency = new ConcurrentHashMap<>();
    private final Map<String, Counter> dlqDeleted = new ConcurrentHashMap<>();
    private final Map<String, Counter> dlqCleared = new ConcurrentHashMap<>();

    public ReliabilityMicrometerCollector(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordDlqReplay(String topic, int partitionId, boolean success, long durationNanos) {
        if (success) {
            counter(replaySuccess, "redis_streaming_dlq_replay_success_total", topic, partitionId).increment();
        } else {
            counter(replayFailure, "redis_streaming_dlq_replay_failure_total", topic, partitionId).increment();
        }
        timer(replayLatency, "redis_streaming_dlq_replay_latency_ms", topic, partitionId)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void incDlqDelete(String topic) {
        counter(dlqDeleted, "redis_streaming_dlq_deleted_total", topic, -1).increment();
    }

    @Override
    public void incDlqClear(String topic, long count) {
        Counter c = counter(dlqCleared, "redis_streaming_dlq_cleared_total", topic, -1);
        if (count > 0) c.increment(count);
    }

    private Counter counter(Map<String, Counter> cache, String name, String topic, int pid) {
        String key = name + "|" + topic + "|" + pid;
        return cache.computeIfAbsent(key, k -> Counter.builder(name)
                .tag("topic", topic)
                .tag("partition", Integer.toString(pid))
                .register(registry));
    }

    private Timer timer(Map<String, Timer> cache, String name, String topic, int pid) {
        String key = name + "|" + topic + "|" + pid;
        return cache.computeIfAbsent(key, k -> Timer.builder(name)
                .tag("topic", topic)
                .tag("partition", Integer.toString(pid))
                .register(registry));
    }
}

