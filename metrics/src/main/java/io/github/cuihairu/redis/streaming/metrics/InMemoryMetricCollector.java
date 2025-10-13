package io.github.cuihairu.redis.streaming.metrics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory implementation of MetricCollector.
 * Stores metrics in memory using thread-safe data structures.
 */
public class InMemoryMetricCollector implements MetricCollector {

    private static final long serialVersionUID = 1L;

    private final Map<String, AtomicLong> counters;
    private final Map<String, Double> gauges;
    private final Map<String, Metric> metrics;

    public InMemoryMetricCollector() {
        this.counters = new ConcurrentHashMap<>();
        this.gauges = new ConcurrentHashMap<>();
        this.metrics = new ConcurrentHashMap<>();
    }

    @Override
    public void incrementCounter(String name) {
        incrementCounter(name, 1L);
    }

    @Override
    public void incrementCounter(String name, long amount) {
        AtomicLong counter = counters.computeIfAbsent(name, k -> new AtomicLong(0));
        long newValue = counter.addAndGet(amount);
        updateMetric(name, MetricType.COUNTER, newValue, null);
    }

    @Override
    public void setGauge(String name, double value) {
        gauges.put(name, value);
        updateMetric(name, MetricType.GAUGE, value, null);
    }

    @Override
    public void recordHistogram(String name, double value) {
        updateMetric(name, MetricType.HISTOGRAM, value, null);
    }

    @Override
    public void markMeter(String name) {
        AtomicLong meter = counters.computeIfAbsent(name + ".meter", k -> new AtomicLong(0));
        long newValue = meter.incrementAndGet();
        updateMetric(name, MetricType.METER, newValue, null);
    }

    @Override
    public void recordTimer(String name, long durationMillis) {
        updateMetric(name, MetricType.TIMER, durationMillis, null);
    }

    @Override
    public void incrementCounter(String name, Map<String, String> tags) {
        incrementCounter(name, 1L);
        if (tags != null && !tags.isEmpty()) {
            String taggedName = buildTaggedName(name, tags);
            AtomicLong counter = counters.computeIfAbsent(taggedName, k -> new AtomicLong(0));
            long newValue = counter.incrementAndGet();
            updateMetric(taggedName, MetricType.COUNTER, newValue, tags);
        }
    }

    @Override
    public void setGauge(String name, double value, Map<String, String> tags) {
        setGauge(name, value);
        if (tags != null && !tags.isEmpty()) {
            String taggedName = buildTaggedName(name, tags);
            gauges.put(taggedName, value);
            updateMetric(taggedName, MetricType.GAUGE, value, tags);
        }
    }

    @Override
    public Map<String, Metric> getMetrics() {
        return new HashMap<>(metrics);
    }

    @Override
    public Metric getMetric(String name) {
        return metrics.get(name);
    }

    @Override
    public void clear() {
        counters.clear();
        gauges.clear();
        metrics.clear();
    }

    /**
     * Get the current value of a counter
     */
    public long getCounterValue(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0L;
    }

    /**
     * Get the current value of a gauge
     */
    public double getGaugeValue(String name) {
        return gauges.getOrDefault(name, 0.0);
    }

    private void updateMetric(String name, MetricType type, double value, Map<String, String> tags) {
        Metric.Builder builder = Metric.builder(name, type).value(value);
        if (tags != null) {
            builder.tags(tags);
        }
        metrics.put(name, builder.build());
    }

    private String buildTaggedName(String name, Map<String, String> tags) {
        StringBuilder sb = new StringBuilder(name);
        tags.forEach((k, v) -> sb.append(".").append(k).append("_").append(v));
        return sb.toString();
    }
}
