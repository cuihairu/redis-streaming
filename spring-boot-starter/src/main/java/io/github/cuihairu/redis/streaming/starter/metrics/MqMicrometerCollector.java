package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.metrics.MqMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-backed detailed MQ metrics (per topic/partition).
 *
 * <p>Tenant tag at the export boundary: the built-in bridge keeps dropping the
 * tenant by default so existing dashboards keep their exact series set; with
 * {@code tenantTag=true} the topic-scoped counters/latency also carry a
 * {@code tenant} tag (docs/Metrics.md「tenant 维度规格」). Distinct
 * {@code tenant×topic} pairs past {@code tenantTopicWarnThreshold} trip a
 * one-shot WARN toward the operator (the documented cardinality budget).</p>
 */
@Slf4j
public class MqMicrometerCollector implements MqMetricsCollector {

    private final MeterRegistry registry;
    private final boolean tenantTag;
    private final int tenantTopicWarnThreshold;
    private final Set<String> tenantTopicPairs = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean cardinalityWarned = new AtomicBoolean();
    private final Map<String, Counter> produced = new ConcurrentHashMap<>();
    private final Map<String, Counter> consumed = new ConcurrentHashMap<>();
    private final Map<String, Counter> acked = new ConcurrentHashMap<>();
    private final Map<String, Counter> retried = new ConcurrentHashMap<>();
    private final Map<String, Counter> dead = new ConcurrentHashMap<>();
    private final Map<String, Counter> payloadMissing = new ConcurrentHashMap<>();
    private final Map<String, Counter> rateLimited = new ConcurrentHashMap<>();
    private final Map<String, Counter> dlqReplaySuccess = new ConcurrentHashMap<>();
    private final Map<String, Counter> dlqReplayFailure = new ConcurrentHashMap<>();
    private final Map<String, Timer> dlqReplayLatency = new ConcurrentHashMap<>();
    private final Map<String, Counter> dlqDeleted = new ConcurrentHashMap<>();
    private final Map<String, Counter> dlqCleared = new ConcurrentHashMap<>();
    private final Map<String, Timer> handleLatency = new ConcurrentHashMap<>();
    private final Map<String, Timer> backpressureWait = new ConcurrentHashMap<>();
    private final Map<String, Counter> backpressureWaitCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public MqMicrometerCollector(MeterRegistry registry) {
        this(registry, false, 1000);
    }

    public MqMicrometerCollector(MeterRegistry registry, boolean tenantTag) {
        this(registry, tenantTag, 1000);
    }

    MqMicrometerCollector(MeterRegistry registry, boolean tenantTag, int tenantTopicWarnThreshold) {
        this.registry = registry;
        this.tenantTag = tenantTag;
        this.tenantTopicWarnThreshold = tenantTopicWarnThreshold;
    }

    boolean cardinalityWarned() {
        return cardinalityWarned.get();
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

    // Tenant-aware variants: default delegates to the legacy series (current
    // behavior); only when the export flag is on do they carry the tenant tag.
    @Override
    public void incProduced(String tenant, String topic, int partitionId) {
        if (!tenantTag) { incProduced(topic, partitionId); return; }
        tenantCounter(produced, "redis_streaming_mq_produced_total", tenant, topic, partitionId).increment();
    }

    @Override
    public void incConsumed(String tenant, String topic, int partitionId) {
        if (!tenantTag) { incConsumed(topic, partitionId); return; }
        tenantCounter(consumed, "redis_streaming_mq_consumed_total", tenant, topic, partitionId).increment();
    }

    @Override
    public void incAcked(String tenant, String topic, int partitionId) {
        if (!tenantTag) { incAcked(topic, partitionId); return; }
        tenantCounter(acked, "redis_streaming_mq_acked_total", tenant, topic, partitionId).increment();
    }

    @Override
    public void incRetried(String tenant, String topic, int partitionId) {
        if (!tenantTag) { incRetried(topic, partitionId); return; }
        tenantCounter(retried, "redis_streaming_mq_retried_total", tenant, topic, partitionId).increment();
    }

    @Override
    public void incDeadLetter(String tenant, String topic, int partitionId) {
        if (!tenantTag) { incDeadLetter(topic, partitionId); return; }
        tenantCounter(dead, "redis_streaming_mq_dead_total", tenant, topic, partitionId).increment();
    }

    @Override
    public void recordHandleLatency(String tenant, String topic, int partitionId, long millis) {
        if (!tenantTag) { recordHandleLatency(topic, partitionId, millis); return; }
        tenantTimer(handleLatency, "redis_streaming_mq_handle_latency_ms", tenant, topic, partitionId)
                .record(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** Sends rejected by the {@link io.github.cuihairu.redis.streaming.mq.config.SendQuota} (tags: tenant, topic). */
    @Override
    public void incRateLimited(String tenant, String topic) {
        String key = "redis_streaming_mq_rate_limited_total|" + tenant + "|" + topic;
        rateLimited.computeIfAbsent(key, k -> {
            trackTenantTopicPair(tenant, topic);
            return Counter.builder("redis_streaming_mq_rate_limited_total")
                    .tag("tenant", tenant)
                    .tag("topic", topic)
                    .register(registry);
        }).increment();
    }

    @Override
    public void setInFlight(String consumerName, long inFlight, int maxInFlight) {
        gauge("redis_streaming_mq_inflight", "consumer", consumerName).set(Math.max(0L, inFlight));
        gauge("redis_streaming_mq_max_inflight", "consumer", consumerName).set(Math.max(0L, maxInFlight));
    }

    @Override
    public void recordDlqReplay(String topic, int partitionId, boolean success, long durationNanos) {
        if (success) {
            counter(dlqReplaySuccess, "redis_streaming_dlq_replay_success_total", topic, partitionId).increment();
        } else {
            counter(dlqReplayFailure, "redis_streaming_dlq_replay_failure_total", topic, partitionId).increment();
        }
        timer(dlqReplayLatency, "redis_streaming_dlq_replay_latency_ms", topic, partitionId)
                .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
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

    private Counter tenantCounter(Map<String, Counter> cache, String name, String tenant, String topic, int pid) {
        String key = name + "|" + tenant + "|" + topic + "|" + pid;
        return cache.computeIfAbsent(key, k -> {
            trackTenantTopicPair(tenant, topic);
            return Counter.builder(name)
                    .tag("tenant", tenant)
                    .tag("topic", topic)
                    .tag("partition", Integer.toString(pid))
                    .register(registry);
        });
    }

    private Timer tenantTimer(Map<String, Timer> cache, String name, String tenant, String topic, int pid) {
        String key = name + "|" + tenant + "|" + topic + "|" + pid;
        return cache.computeIfAbsent(key, k -> {
            trackTenantTopicPair(tenant, topic);
            return Timer.builder(name)
                    .tag("tenant", tenant)
                    .tag("topic", topic)
                    .tag("partition", Integer.toString(pid))
                    .register(registry);
        });
    }

    /** One-shot WARN when distinct tenant×topic pairs pass the documented cardinality budget. */
    private void trackTenantTopicPair(String tenant, String topic) {
        if (tenantTopicPairs.add(tenant + "|" + topic)
                && tenantTopicPairs.size() > tenantTopicWarnThreshold
                && cardinalityWarned.compareAndSet(false, true)) {
            log.warn("MQ tenant metric cardinality: {} tenant×topic pairs past the configured budget of {} "
                            + "(redis-streaming.mq.metrics-tenant-tag); consider a narrower tenant set or "
                            + "tenant-separated deployments (docs/Metrics.md「tenant 维度规格」)",
                    tenantTopicPairs.size(), tenantTopicWarnThreshold);
        }
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
