package io.github.cuihairu.redis.streaming.metrics.impl;

import io.github.cuihairu.redis.streaming.metrics.MetricsCollector;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple in-memory metrics collector for basic use cases
 */
@Slf4j
public class SimpleMetricsCollector implements MetricsCollector {

    private final String name;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    // Metric storage
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Double>> gauges = new ConcurrentHashMap<>();
    private final Map<String, TimerStats> timers = new ConcurrentHashMap<>();
    private final Map<String, HistogramStats> histograms = new ConcurrentHashMap<>();

    public SimpleMetricsCollector(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void recordCounter(String metricName, double value, Map<String, String> tags) {
        if (!enabled.get()) {
            return;
        }

        String key = createKey(metricName, tags);
        counters.computeIfAbsent(key, k -> new AtomicLong(0))
                .addAndGet((long) value);
    }

    @Override
    public void recordGauge(String metricName, double value, Map<String, String> tags) {
        if (!enabled.get()) {
            return;
        }

        String key = createKey(metricName, tags);
        gauges.computeIfAbsent(key, k -> new AtomicReference<>(0.0))
                .set(value);
    }

    @Override
    public void recordTimer(String metricName, long duration, Map<String, String> tags) {
        if (!enabled.get()) {
            return;
        }

        String key = createKey(metricName, tags);
        timers.computeIfAbsent(key, k -> new TimerStats())
                .record(duration);
    }

    @Override
    public void recordHistogram(String metricName, double value, Map<String, String> tags) {
        if (!enabled.get()) {
            return;
        }

        String key = createKey(metricName, tags);
        histograms.computeIfAbsent(key, k -> new HistogramStats())
                .record(value);
    }

    @Override
    public TimingContext startTimer(String metricName, Map<String, String> tags) {
        if (!enabled.get()) {
            return new NoOpTimingContext();
        }

        return new SimpleTimingContext(metricName, tags, System.currentTimeMillis());
    }

    @Override
    public Map<String, Double> getCurrentMetrics() {
        Map<String, Double> metrics = new ConcurrentHashMap<>();

        // Add counters
        counters.forEach((key, value) ->
            metrics.put(key + ".count", (double) value.get()));

        // Add gauges
        gauges.forEach((key, value) ->
            metrics.put(key + ".value", value.get()));

        // Add timer stats
        timers.forEach((key, stats) -> {
            metrics.put(key + ".count", (double) stats.getCount());
            metrics.put(key + ".totalTime", (double) stats.getTotalTime());
            metrics.put(key + ".mean", stats.getMean());
            metrics.put(key + ".min", (double) stats.getMin());
            metrics.put(key + ".max", (double) stats.getMax());
        });

        // Add histogram stats
        histograms.forEach((key, stats) -> {
            metrics.put(key + ".count", (double) stats.getCount());
            metrics.put(key + ".totalAmount", stats.getTotalAmount());
            metrics.put(key + ".mean", stats.getMean());
            metrics.put(key + ".min", stats.getMin());
            metrics.put(key + ".max", stats.getMax());
        });

        return metrics;
    }

    @Override
    public void clear() {
        counters.clear();
        gauges.clear();
        timers.clear();
        histograms.clear();
        log.debug("Cleared all metrics for collector: {}", name);
    }

    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    private String createKey(String metricName, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return metricName;
        }

        StringBuilder keyBuilder = new StringBuilder(metricName);
        tags.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> keyBuilder.append("|")
                        .append(entry.getKey())
                        .append("=")
                        .append(entry.getValue()));

        return keyBuilder.toString();
    }

    /**
     * Simple timing context implementation
     */
    private class SimpleTimingContext implements TimingContext {
        private final String metricName;
        private final Map<String, String> tags;
        private final long startTime;
        private volatile boolean stopped = false;

        public SimpleTimingContext(String metricName, Map<String, String> tags, long startTime) {
            this.metricName = metricName;
            this.tags = tags;
            this.startTime = startTime;
        }

        @Override
        public void stop() {
            if (!stopped) {
                stopped = true;
                long duration = System.currentTimeMillis() - startTime;
                recordTimer(metricName, duration, tags);
            }
        }
    }

    /**
     * No-op timing context
     */
    private static class NoOpTimingContext implements TimingContext {
        @Override
        public void stop() {
            // No-op
        }
    }

    /**
     * Timer statistics
     */
    private static class TimerStats {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalTime = new AtomicLong(0);
        private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

        public void record(long duration) {
            count.incrementAndGet();
            totalTime.addAndGet(duration);
            updateMin(duration);
            updateMax(duration);
        }

        private void updateMin(long duration) {
            min.updateAndGet(current -> Math.min(current, duration));
        }

        private void updateMax(long duration) {
            max.updateAndGet(current -> Math.max(current, duration));
        }

        public long getCount() {
            return count.get();
        }

        public long getTotalTime() {
            return totalTime.get();
        }

        public double getMean() {
            long c = count.get();
            return c > 0 ? (double) totalTime.get() / c : 0.0;
        }

        public long getMin() {
            long value = min.get();
            return value == Long.MAX_VALUE ? 0 : value;
        }

        public long getMax() {
            long value = max.get();
            return value == Long.MIN_VALUE ? 0 : value;
        }
    }

    /**
     * Histogram statistics
     */
    private static class HistogramStats {
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicReference<Double> totalAmount = new AtomicReference<>(0.0);
        private final AtomicReference<Double> min = new AtomicReference<>(Double.MAX_VALUE);
        private final AtomicReference<Double> max = new AtomicReference<>(Double.MIN_VALUE);

        public void record(double value) {
            count.incrementAndGet();
            totalAmount.updateAndGet(current -> current + value);
            updateMin(value);
            updateMax(value);
        }

        private void updateMin(double value) {
            min.updateAndGet(current -> Math.min(current, value));
        }

        private void updateMax(double value) {
            max.updateAndGet(current -> Math.max(current, value));
        }

        public long getCount() {
            return count.get();
        }

        public double getTotalAmount() {
            return totalAmount.get();
        }

        public double getMean() {
            long c = count.get();
            return c > 0 ? totalAmount.get() / c : 0.0;
        }

        public double getMin() {
            double value = min.get();
            return value == Double.MAX_VALUE ? 0.0 : value;
        }

        public double getMax() {
            double value = max.get();
            return value == Double.MIN_VALUE ? 0.0 : value;
        }
    }
}