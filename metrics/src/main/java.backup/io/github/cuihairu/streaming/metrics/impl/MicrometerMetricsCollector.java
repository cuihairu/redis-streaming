package io.github.cuihairu.redis.streaming.metrics.impl;

import io.github.cuihairu.redis.streaming.metrics.MetricsCollector;
import io.github.cuihairu.redis.streaming.metrics.MetricsEventListener;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Micrometer-based metrics collector implementation
 */
@Slf4j
public class MicrometerMetricsCollector implements MetricsCollector {

    private final String name;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private volatile MetricsEventListener eventListener;

    // Cache for meters to avoid recreation
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, DistributionSummary> histograms = new ConcurrentHashMap<>();

    public MicrometerMetricsCollector(String name, MeterRegistry meterRegistry) {
        this.name = name;
        this.meterRegistry = meterRegistry;
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

        try {
            String key = createKey(metricName, tags);
            Counter counter = counters.computeIfAbsent(key, k -> {
                Counter.Builder builder = Counter.builder(metricName);
                if (tags != null) {
                    tags.forEach(builder::tag);
                }
                return builder.register(meterRegistry);
            });

            counter.increment(value);
            notifyMetricRecorded(metricName, value, tags);

        } catch (Exception e) {
            log.error("Error recording counter metric: {}", metricName, e);
            notifyError(e);
        }
    }

    @Override
    public void recordGauge(String metricName, double value, Map<String, String> tags) {
        if (!enabled.get()) {
            return;
        }

        try {
            String key = createKey(metricName, tags);

            // Use number wrapper object for gauge
            java.util.concurrent.atomic.AtomicReference<Double> atomicValue =
                new java.util.concurrent.atomic.AtomicReference<>(value);

            Gauge.Builder<java.util.concurrent.atomic.AtomicReference<Double>> builder =
                Gauge.builder(metricName, atomicValue, ref -> ref.get());

            if (tags != null) {
                tags.forEach(builder::tag);
            }

            builder.register(meterRegistry);

            notifyMetricRecorded(metricName, value, tags);

        } catch (Exception e) {
            log.error("Error recording gauge metric: {}", metricName, e);
            notifyError(e);
        }
    }

    @Override
    public void recordTimer(String metricName, long duration, Map<String, String> tags) {
        if (!enabled.get()) {
            return;
        }

        try {
            String key = createKey(metricName, tags);
            Timer timer = timers.computeIfAbsent(key, k -> {
                Timer.Builder builder = Timer.builder(metricName);
                if (tags != null) {
                    tags.forEach(builder::tag);
                }
                return builder.register(meterRegistry);
            });

            timer.record(duration, TimeUnit.MILLISECONDS);
            notifyMetricRecorded(metricName, duration, tags);

        } catch (Exception e) {
            log.error("Error recording timer metric: {}", metricName, e);
            notifyError(e);
        }
    }

    @Override
    public void recordHistogram(String metricName, double value, Map<String, String> tags) {
        if (!enabled.get()) {
            return;
        }

        try {
            String key = createKey(metricName, tags);
            DistributionSummary histogram = histograms.computeIfAbsent(key, k -> {
                DistributionSummary.Builder builder = DistributionSummary.builder(metricName);
                if (tags != null) {
                    tags.forEach(builder::tag);
                }
                return builder.register(meterRegistry);
            });

            histogram.record(value);
            notifyMetricRecorded(metricName, value, tags);

        } catch (Exception e) {
            log.error("Error recording histogram metric: {}", metricName, e);
            notifyError(e);
        }
    }

    @Override
    public TimingContext startTimer(String metricName, Map<String, String> tags) {
        if (!enabled.get()) {
            return new NoOpTimingContext();
        }

        try {
            String key = createKey(metricName, tags);
            Timer timer = timers.computeIfAbsent(key, k -> {
                Timer.Builder builder = Timer.builder(metricName);
                if (tags != null) {
                    tags.forEach(builder::tag);
                }
                return builder.register(meterRegistry);
            });

            Timer.Sample sample = Timer.start(meterRegistry);
            return new MicrometerTimingContext(sample, timer, metricName, tags);

        } catch (Exception e) {
            log.error("Error starting timer: {}", metricName, e);
            notifyError(e);
            return new NoOpTimingContext();
        }
    }

    @Override
    public Map<String, Double> getCurrentMetrics() {
        Map<String, Double> metrics = new ConcurrentHashMap<>();

        try {
            meterRegistry.getMeters().forEach(meter -> {
                String meterName = meter.getId().getName();

                if (meter instanceof Counter) {
                    metrics.put(meterName + ".count", ((Counter) meter).count());
                } else if (meter instanceof Timer) {
                    Timer timer = (Timer) meter;
                    metrics.put(meterName + ".count", (double) timer.count());
                    metrics.put(meterName + ".totalTime", timer.totalTime(TimeUnit.MILLISECONDS));
                    metrics.put(meterName + ".mean", timer.mean(TimeUnit.MILLISECONDS));
                } else if (meter instanceof DistributionSummary) {
                    DistributionSummary summary = (DistributionSummary) meter;
                    metrics.put(meterName + ".count", (double) summary.count());
                    metrics.put(meterName + ".totalAmount", summary.totalAmount());
                    metrics.put(meterName + ".mean", summary.mean());
                } else if (meter instanceof Gauge) {
                    metrics.put(meterName + ".value", ((Gauge) meter).value());
                }
            });

        } catch (Exception e) {
            log.error("Error getting current metrics", e);
            notifyError(e);
        }

        return metrics;
    }

    @Override
    public void clear() {
        try {
            counters.clear();
            gauges.clear();
            timers.clear();
            histograms.clear();
            meterRegistry.clear();
            log.debug("Cleared all metrics for collector: {}", name);

        } catch (Exception e) {
            log.error("Error clearing metrics", e);
            notifyError(e);
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    @Override
    public void setEnabled(boolean enabled) {
        boolean oldValue = this.enabled.getAndSet(enabled);
        if (oldValue != enabled && eventListener != null) {
            try {
                eventListener.onCollectorEnabledChanged(name, enabled);
            } catch (Exception e) {
                log.warn("Error notifying enabled state change", e);
            }
        }
    }

    /**
     * Set the event listener
     *
     * @param listener the event listener
     */
    public void setEventListener(MetricsEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * Get the underlying Micrometer registry
     *
     * @return the meter registry
     */
    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
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

    private void notifyMetricRecorded(String metricName, double value, Map<String, String> tags) {
        if (eventListener != null) {
            try {
                eventListener.onMetricRecorded(name, metricName, value, tags);
            } catch (Exception e) {
                log.warn("Error notifying metric recorded", e);
            }
        }
    }

    private void notifyError(Throwable error) {
        if (eventListener != null) {
            try {
                eventListener.onMetricsError(name, error);
            } catch (Exception e) {
                log.warn("Error notifying metrics error", e);
            }
        }
    }

    /**
     * Micrometer-based timing context implementation
     */
    private class MicrometerTimingContext implements TimingContext {
        private final Timer.Sample sample;
        private final Timer timer;
        private final String metricName;
        private final Map<String, String> tags;
        private volatile boolean stopped = false;

        public MicrometerTimingContext(Timer.Sample sample, Timer timer, String metricName, Map<String, String> tags) {
            this.sample = sample;
            this.timer = timer;
            this.metricName = metricName;
            this.tags = tags;
        }

        @Override
        public void stop() {
            if (!stopped) {
                stopped = true;
                try {
                    long duration = sample.stop(timer);
                    notifyMetricRecorded(metricName, duration, tags);
                } catch (Exception e) {
                    log.error("Error stopping timer: {}", metricName, e);
                    notifyError(e);
                }
            }
        }
    }

    /**
     * No-op timing context for when collector is disabled
     */
    private static class NoOpTimingContext implements TimingContext {
        @Override
        public void stop() {
            // No-op
        }
    }
}