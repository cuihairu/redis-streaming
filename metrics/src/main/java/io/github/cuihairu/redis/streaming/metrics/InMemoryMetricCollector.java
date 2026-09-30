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
    /** For HISTOGRAM: stores count/sum/max/min to aggregate; value holds last sample for backward compat */
    private final Map<String, HistogramState> histogramStates;

    public InMemoryMetricCollector() {
        this.counters = new ConcurrentHashMap<>();
        this.gauges = new ConcurrentHashMap<>();
        this.metrics = new ConcurrentHashMap<>();
        this.histogramStates = new ConcurrentHashMap<>();
    }

    @Override
    public void incrementCounter(String name) {
        incrementCounter(name, 1L);
    }

    @Override
    public void incrementCounter(String name, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Counter increment amount must be non-negative");
        }
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
        HistogramState state = histogramStates.computeIfAbsent(name, k -> new HistogramState());
        state.record(value);
        // Metric value holds last sample for backward compatibility; aggregate stats in state
        updateMetric(name, MetricType.HISTOGRAM, value, null);
    }

    @Override
    public void markMeter(String name) {
        // Use a distinct internal key to avoid colliding with incrementCounter(name)
        String meterKey = name + ".meter";
        AtomicLong meter = counters.computeIfAbsent(meterKey, k -> new AtomicLong(0));
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
        histogramStates.clear();
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

    /**
     * Get histogram aggregate state (count/sum/max/min) if available.
     * Returns null if no histogram recorded for this name.
     */
    public HistogramState getHistogramState(String name) {
        return histogramStates.get(name);
    }

    private void updateMetric(String name, MetricType type, double value, Map<String, String> tags) {
        Metric.Builder builder = Metric.builder(name, type).value(value);
        if (tags != null) {
            builder.tags(tags);
        }
        metrics.put(name, builder.build());
    }

    private String buildTaggedName(String name, Map<String, String> tags) {
        // sorted by tag key: HashMap iteration order differs per map, so the same tags
        // in a different insertion order used to mint two distinct series that each
        // showed half the traffic (mirrors PrometheusMetricCollector's sorting)
        StringBuilder sb = new StringBuilder(name);
        tags.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(".").append(e.getKey()).append("_").append(e.getValue()));
        return sb.toString();
    }

    /**
     * Aggregate state for a histogram metric.
     */
    public static class HistogramState {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong sum = new AtomicLong(0);
        private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);

        void record(double value) {
            count.incrementAndGet();
            long lval = Double.doubleToLongBits(value);
            sum.addAndGet(lval);
            while (true) {
                long curMax = max.get();
                if (lval <= curMax || max.compareAndSet(curMax, lval)) break;
            }
            while (true) {
                long curMin = min.get();
                if (lval >= curMin || min.compareAndSet(curMin, lval)) break;
            }
        }

        public long getCount() { return count.get(); }
        public double getSum() { return Double.longBitsToDouble(sum.get()); }
        public double getMax() { return Double.longBitsToDouble(max.get()); }
        public double getMin() { return Double.longBitsToDouble(min.get()); }
        public double getMean() { return count.get() > 0 ? getSum() / count.get() : 0.0; }
    }
}
