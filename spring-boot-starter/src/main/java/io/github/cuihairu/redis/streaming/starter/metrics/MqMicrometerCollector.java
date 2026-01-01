package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.metrics.MqMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-backed detailed MQ metrics (per topic/partition).
 */
public class MqMicrometerCollector implements MqMetricsCollector {

    private final MeterRegistry registry;
    private final Map<String, Counter> produced = new ConcurrentHashMap<>();
    private final Map<String, Counter> consumed = new ConcurrentHashMap<>();
    private final Map<String, Counter> acked = new ConcurrentHashMap<>();
    private final Map<String, Counter> retried = new ConcurrentHashMap<>();
    private final Map<String, Counter> dead = new ConcurrentHashMap<>();
    private final Map<String, Counter> payloadMissing = new ConcurrentHashMap<>();
    private final Map<String, Timer> handleLatency = new ConcurrentHashMap<>();
    private final Map<String, Timer> backpressureWait = new ConcurrentHashMap<>();
    private final Map<String, Counter> backpressureWaitCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public MqMicrometerCollector(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void incProduced(String topic, int partitionId) {
        counter(produced, "redis_streaming_mq_produced_total", topic, partitionId).increment();
    }

    @Override
    public void incConsumed(String topic, int partitionId) {
        counter(consumed, "redis_streaming_mq_consumed_total", topic, partitionId).increment();
    }

    @Override
    public void incAcked(String topic, int partitionId) {
        counter(acked, "redis_streaming_mq_acked_total", topic, partitionId).increment();
    }

    @Override
    public void incRetried(String topic, int partitionId) {
        counter(retried, "redis_streaming_mq_retried_total", topic, partitionId).increment();
    }

    @Override
    public void incDeadLetter(String topic, int partitionId) {
        counter(dead, "redis_streaming_mq_dead_total", topic, partitionId).increment();
    }

    @Override
    public void recordHandleLatency(String topic, int partitionId, long millis) {
        timer(handleLatency, "redis_streaming_mq_handle_latency_ms", topic, partitionId)
                .record(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void incPayloadMissing(String topic, int partitionId) {
        counter(payloadMissing, "redis_streaming_mq_payload_missing_total", topic, partitionId).increment();
    }

    @Override
    public void setInFlight(String consumerName, long inFlight, int maxInFlight) {
        gauge("redis_streaming_mq_inflight", "consumer", consumerName).set(Math.max(0L, inFlight));
        gauge("redis_streaming_mq_max_inflight", "consumer", consumerName).set(Math.max(0L, maxInFlight));
    }

    @Override
    public void recordBackpressureWait(String consumerName, long waitMillis) {
        if (waitMillis < 0) {
            return;
        }
        backpressureWaitCounter("redis_streaming_mq_backpressure_wait_total", consumerName).increment();
        backpressureWaitTimer("redis_streaming_mq_backpressure_wait_ms", consumerName)
                .record(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void setEligiblePartitions(String consumerName, String topic, String consumerGroup, int eligibleCount) {
        gauge("redis_streaming_mq_eligible_partitions",
                "consumer", consumerName,
                "topic", topic,
                "group", consumerGroup).set(Math.max(0L, eligibleCount));
    }

    @Override
    public void setLeasedPartitions(String consumerName, String topic, String consumerGroup, int leasedCount) {
        gauge("redis_streaming_mq_leased_partitions",
                "consumer", consumerName,
                "topic", topic,
                "group", consumerGroup).set(Math.max(0L, leasedCount));
    }

    @Override
    public void setMaxLeasedPartitions(String consumerName, int maxLeasedPartitions) {
        gauge("redis_streaming_mq_max_leased_partitions", "consumer", consumerName).set(Math.max(0L, maxLeasedPartitions));
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

    private AtomicLong gauge(String name, String tagK, String tagV) {
        return gauge(name, tagK, tagV, null, null, null, null);
    }

    private AtomicLong gauge(String name, String tagK1, String tagV1, String tagK2, String tagV2, String tagK3, String tagV3) {
        StringBuilder key = new StringBuilder(name).append("|").append(tagK1).append("|").append(tagV1);
        if (tagK2 != null) {
            key.append("|").append(tagK2).append("|").append(tagV2);
        }
        if (tagK3 != null) {
            key.append("|").append(tagK3).append("|").append(tagV3);
        }
        String cacheKey = key.toString();
        return gauges.computeIfAbsent(cacheKey, k -> {
            AtomicLong holder = new AtomicLong(0L);
            Gauge.Builder<AtomicLong> b = Gauge.builder(name, holder, v -> (double) v.get())
                    .tag(tagK1, tagV1);
            if (tagK2 != null) {
                b.tag(tagK2, tagV2);
            }
            if (tagK3 != null) {
                b.tag(tagK3, tagV3);
            }
            b.register(registry);
            return holder;
        });
    }

    private Counter backpressureWaitCounter(String name, String consumerName) {
        String key = name + "|" + consumerName;
        return backpressureWaitCount.computeIfAbsent(key, k -> Counter.builder(name)
                .tag("consumer", consumerName)
                .register(registry));
    }

    private Timer backpressureWaitTimer(String name, String consumerName) {
        String key = name + "|" + consumerName;
        return backpressureWait.computeIfAbsent(key, k -> Timer.builder(name)
                .tag("consumer", consumerName)
                .register(registry));
    }
}
