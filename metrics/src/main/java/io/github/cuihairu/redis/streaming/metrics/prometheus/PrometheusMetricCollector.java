package io.github.cuihairu.redis.streaming.metrics.prometheus;

import io.github.cuihairu.redis.streaming.metrics.Metric;
import io.github.cuihairu.redis.streaming.metrics.MetricCollector;
import io.github.cuihairu.redis.streaming.metrics.MetricType;
import io.prometheus.client.Counter;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prometheus-based metric collector.
 * Bridges the streaming framework metrics to Prometheus metrics.
 */
@Slf4j
public class PrometheusMetricCollector implements MetricCollector {

    private static final long serialVersionUID = 1L;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();
    private final Map<String, String> labelSchemaByPrometheusName = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counterValues = new ConcurrentHashMap<>();
    private final Map<String, Metric> metrics = new ConcurrentHashMap<>();
    private final String namespace;
    private final transient CollectorRegistry registry;

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
        this(namespace, CollectorRegistry.defaultRegistry);
    }

    /**
     * Create a Prometheus metric collector with a custom namespace and registry.
     *
     * <p>Use a dedicated {@link CollectorRegistry} for tests to avoid cross-test registration
     * conflicts.</p>
     */
    public PrometheusMetricCollector(String namespace, CollectorRegistry registry) {
        this.namespace = namespace;
        this.registry = registry == null ? CollectorRegistry.defaultRegistry : registry;
        log.info("Created Prometheus metric collector with namespace: {}", namespace);
    }

    @Override
    public void incrementCounter(String name) {
        incrementCounter(name, 1L);
    }

    @Override
    public void incrementCounter(String name, long amount) {
        String metricKey = name;
        String sanitized = sanitizeName(name);
        Counter counter = counters.computeIfAbsent(sanitized, k ->
                Counter.build()
                        .namespace(namespace)
                        .name(k)
                        .help("Counter metric: " + name)
                        .register(registry)
        );
        counter.inc(amount);

        long value = counterValues.computeIfAbsent(metricKey, k -> new AtomicLong(0)).addAndGet(amount);
        updateMetric(metricKey, MetricType.COUNTER, value, null);
    }

    @Override
    public void setGauge(String name, double value) {
        String metricKey = name;
        String sanitized = sanitizeName(name);
        Gauge gauge = gauges.computeIfAbsent(sanitized, k ->
                Gauge.build()
                        .namespace(namespace)
                        .name(k)
                        .help("Gauge metric: " + name)
                        .register(registry)
        );
        gauge.set(value);
        updateMetric(metricKey, MetricType.GAUGE, value, null);
    }

    @Override
    public void recordHistogram(String name, double value) {
        String metricKey = name;
        String sanitized = sanitizeName(name);
        Histogram histogram = histograms.computeIfAbsent(sanitized, k ->
                Histogram.build()
                        .namespace(namespace)
                        .name(k)
                        .help("Histogram metric: " + name)
                        .register(registry)
        );
        histogram.observe(value);
        updateMetric(metricKey, MetricType.HISTOGRAM, value, null);
    }

    @Override
    public void markMeter(String name) {
        incrementCounter(name, 1L);
        updateMetric(name, MetricType.METER, getCounterValue(name), null);
    }

    @Override
    public void recordTimer(String name, long durationMillis) {
        recordHistogram(name, durationMillis / 1000.0); // Convert to seconds for Prometheus
        updateMetric(name, MetricType.TIMER, durationMillis, null);
    }

    @Override
    public void incrementCounter(String name, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            incrementCounter(name, 1L);
            return;
        }

        String metricKey = buildTaggedName(name, tags);
        String sanitized = sanitizeName(name);

        String[] labelNames = sortedLabelNames(tags);
        String[] labelValues = labelValues(tags, labelNames);

        String key = sanitized + "_labeled";
        validateLabelSchema(key, labelNames);
        Counter counter = counters.computeIfAbsent(key, k ->
                Counter.build()
                        .namespace(namespace)
                        .name(key)
                        .help("Counter metric: " + name)
                        .labelNames(labelNames)
                        .register(registry)
        );

        counter.labels(labelValues).inc();
        long value = counterValues.computeIfAbsent(metricKey, k -> new AtomicLong(0)).incrementAndGet();
        updateMetric(metricKey, MetricType.COUNTER, value, tags);
    }

    @Override
    public void setGauge(String name, double value, Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            setGauge(name, value);
            return;
        }

        String metricKey = buildTaggedName(name, tags);
        String sanitized = sanitizeName(name);

        String[] labelNames = sortedLabelNames(tags);
        String[] labelValues = labelValues(tags, labelNames);

        String key = sanitized + "_labeled";
        validateLabelSchema(key, labelNames);
        Gauge gauge = gauges.computeIfAbsent(key, k ->
                Gauge.build()
                        .namespace(namespace)
                        .name(key)
                        .help("Gauge metric: " + name)
                        .labelNames(labelNames)
                        .register(registry)
        );

        gauge.labels(labelValues).set(value);
        updateMetric(metricKey, MetricType.GAUGE, value, tags);
    }

    @Override
    public Metric getMetric(String name) {
        return metrics.get(name);
    }

    @Override
    public Map<String, Metric> getMetrics() {
        return new ConcurrentHashMap<>(metrics);
    }

    @Override
    public void clear() {
        for (Counter c : counters.values()) {
            registry.unregister(c);
        }
        for (Gauge g : gauges.values()) {
            registry.unregister(g);
        }
        for (Histogram h : histograms.values()) {
            registry.unregister(h);
        }
        counters.clear();
        gauges.clear();
        histograms.clear();
        labelSchemaByPrometheusName.clear();
        counterValues.clear();
        metrics.clear();
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

    private void updateMetric(String name, MetricType type, double value, Map<String, String> tags) {
        Metric.Builder builder = Metric.builder(name, type).value(value);
        if (tags != null && !tags.isEmpty()) {
            builder.tags(tags);
        }
        metrics.put(name, builder.build());
    }

    private long getCounterValue(String name) {
        AtomicLong value = counterValues.get(name);
        return value == null ? 0L : value.get();
    }

    private static String buildTaggedName(String name, Map<String, String> tags) {
        StringBuilder sb = new StringBuilder(name);
        tags.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(".").append(e.getKey()).append("_").append(e.getValue()));
        return sb.toString();
    }

    private static String[] sortedLabelNames(Map<String, String> tags) {
        return tags.keySet().stream().sorted().toArray(String[]::new);
    }

    private static String[] labelValues(Map<String, String> tags, String[] labelNames) {
        String[] values = new String[labelNames.length];
        for (int i = 0; i < labelNames.length; i++) {
            String v = tags.get(labelNames[i]);
            values[i] = v == null ? "" : v;
        }
        return values;
    }

    private void validateLabelSchema(String prometheusName, String[] labelNames) {
        String schema = String.join(",", labelNames);
        String existing = labelSchemaByPrometheusName.putIfAbsent(prometheusName, schema);
        if (existing != null && !existing.equals(schema)) {
            throw new IllegalArgumentException(
                    "Inconsistent label schema for metric '" + prometheusName + "': existing=[" + existing + "], new=[" + schema + "]");
        }
    }

    public String getNamespace() {
        return namespace;
    }
}
