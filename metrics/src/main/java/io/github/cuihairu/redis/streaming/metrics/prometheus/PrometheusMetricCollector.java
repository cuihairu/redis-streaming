package io.github.cuihairu.redis.streaming.metrics.prometheus;

import io.github.cuihairu.redis.streaming.metrics.Metric;
import io.github.cuihairu.redis.streaming.metrics.MetricCollector;
import io.github.cuihairu.redis.streaming.metrics.MetricType;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus-based metric collector.
 * Bridges the streaming framework metrics to Prometheus metrics.
 */
@Slf4j
public class PrometheusMetricCollector implements MetricCollector {

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();
    private final String namespace;

    /**
     * Create a Prometheus metric collector with default namespace.
     */
    public PrometheusMetricCollector() {
        this("streaming");
    }

    /**
     * Create a Prometheus metric collector with custom namespace.
     *
     * @param namespace the metrics namespace (e.g., "streaming")
     */
    public PrometheusMetricCollector(String namespace) {
        this.namespace = namespace;
        log.info("Created Prometheus metric collector with namespace: {}", namespace);
    }

    @Override
    public void incrementCounter(String name) {
        incrementCounter(name, 1L);
    }

    @Override
    public void incrementCounter(String name, long amount) {
        String sanitized = sanitizeName(name);
        Counter counter = counters.computeIfAbsent(sanitized, k ->
                Counter.build()
                        .namespace(namespace)
                        .name(k)
                        .help("Counter metric: " + name)
                        .register()
        );
        counter.inc(amount);
    }

    @Override
    public void setGauge(String name, double value) {
        String sanitized = sanitizeName(name);
        Gauge gauge = gauges.computeIfAbsent(sanitized, k ->
                Gauge.build()
                        .namespace(namespace)
                        .name(k)
                        .help("Gauge metric: " + name)
                        .register()
        );
        gauge.set(value);
    }

    @Override
    public void recordHistogram(String name, double value) {
        String sanitized = sanitizeName(name);
        Histogram histogram = histograms.computeIfAbsent(sanitized, k ->
                Histogram.build()
                        .namespace(namespace)
                        .name(k)
                        .help("Histogram metric: " + name)
                        .register()
        );
        histogram.observe(value);
    }

    @Override
    public void markMeter(String name) {
        incrementCounter(name, 1L);
    }

    @Override
    public void recordTimer(String name, long durationMillis) {
        recordHistogram(name, durationMillis / 1000.0); // Convert to seconds
    }

    @Override
    public void incrementCounter(String name, Map<String, String> tags) {
        String sanitized = sanitizeName(name);

        if (tags == null || tags.isEmpty()) {
            incrementCounter(name, 1L);
            return;
        }

        String[] labelNames = tags.keySet().toArray(new String[0]);
        String[] labelValues = tags.values().toArray(new String[0]);

        String key = sanitized + "_labeled";
        Counter counter = counters.computeIfAbsent(key, k ->
                Counter.build()
                        .namespace(namespace)
                        .name(sanitized)
                        .help("Counter metric: " + name)
                        .labelNames(labelNames)
                        .register()
        );

        counter.labels(labelValues).inc();
    }

    @Override
    public void setGauge(String name, double value, Map<String, String> tags) {
        String sanitized = sanitizeName(name);

        if (tags == null || tags.isEmpty()) {
            setGauge(name, value);
            return;
        }

        String[] labelNames = tags.keySet().toArray(new String[0]);
        String[] labelValues = tags.values().toArray(new String[0]);

        String key = sanitized + "_labeled";
        Gauge gauge = gauges.computeIfAbsent(key, k ->
                Gauge.build()
                        .namespace(namespace)
                        .name(sanitized)
                        .help("Gauge metric: " + name)
                        .labelNames(labelNames)
                        .register()
        );

        gauge.labels(labelValues).set(value);
    }

    @Override
    public Metric getMetric(String name) {
        // Prometheus doesn't support reading back metrics easily
        // Return null
        return null;
    }

    @Override
    public Map<String, Metric> getMetrics() {
        // Not supported in Prometheus collector
        return new ConcurrentHashMap<>();
    }

    @Override
    public void clear() {
        counters.clear();
        gauges.clear();
        histograms.clear();
        log.info("Cleared all Prometheus metrics");
    }

    /**
     * Sanitize metric name to conform to Prometheus naming rules.
     * Prometheus metric names must match [a-zA-Z_:][a-zA-Z0-9_:]*
     *
     * @param name the original metric name
     * @return sanitized name
     */
    private String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_:]", "_")
                .replaceAll("^[^a-zA-Z_:]", "_");
    }

    public String getNamespace() {
        return namespace;
    }
}
