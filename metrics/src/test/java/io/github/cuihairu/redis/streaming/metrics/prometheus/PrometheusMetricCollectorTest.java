package io.github.cuihairu.redis.streaming.metrics.prometheus;

import io.github.cuihairu.redis.streaming.metrics.Metric;
import io.github.cuihairu.redis.streaming.metrics.MetricType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PrometheusMetricCollectorTest {

    @Test
    public void testNamespace() {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("ns_" + UUID.randomUUID().toString().replace("-", ""));
        assertTrue(collector.getNamespace().startsWith("ns_"));
    }

    @Test
    public void testBasicMetricRecordingCreatesCollectors() throws Exception {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("ns_" + UUID.randomUUID().toString().replace("-", ""));

        collector.incrementCounter("requests.total", 2);
        collector.markMeter("events.total");
        collector.setGauge("queue.size", 42.0);
        collector.recordHistogram("latency.ms", 12.5);
        collector.recordTimer("duration.ms", 123);

        Map<String, ?> counters = getMapField(collector, "counters");
        Map<String, ?> gauges = getMapField(collector, "gauges");
        Map<String, ?> histograms = getMapField(collector, "histograms");

        // incrementCounter + markMeter both use counter type internally (markMeter delegates to incrementCounter)
        assertEquals(2, counters.size());
        assertEquals(1, gauges.size());
        // recordHistogram + recordTimer both use histogram type internally (recordTimer delegates to recordHistogram)
        assertEquals(2, histograms.size());
    }

    @Test
    public void testTaggedCounterAndGauge() throws Exception {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("ns_" + UUID.randomUUID().toString().replace("-", ""));

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("service", "a");
        tags.put("region", "b");

        collector.incrementCounter("tagged.counter", tags);
        collector.setGauge("tagged.gauge", 1.5, tags);

        Map<String, ?> counters = getMapField(collector, "counters");
        Map<String, ?> gauges = getMapField(collector, "gauges");

        // Both tagged calls use the same label schema key, so one counter and one gauge entry
        assertEquals(1, counters.size());
        assertEquals(1, gauges.size());
    }

    @Test
    public void testEmptyTagsFallBackToUntagged() throws Exception {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("ns_" + UUID.randomUUID().toString().replace("-", ""));

        collector.incrementCounter("fallback.counter", Map.of());
        collector.setGauge("fallback.gauge", 3.14, Map.of());

        Map<String, ?> counters = getMapField(collector, "counters");
        Map<String, ?> gauges = getMapField(collector, "gauges");

        assertEquals(1, counters.size());
        assertEquals(1, gauges.size());
        assertTrue(counters.keySet().stream().noneMatch(k -> k.endsWith("_labeled")));
        assertTrue(gauges.keySet().stream().noneMatch(k -> k.endsWith("_labeled")));
    }

    @Test
    public void testClearResetsLocalCaches() throws Exception {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("ns_" + UUID.randomUUID().toString().replace("-", ""));

        collector.incrementCounter("to.clear");
        collector.setGauge("to.clear.gauge", 1.0);
        collector.recordHistogram("to.clear.hist", 2.0);

        collector.clear();

        Map<String, ?> counters = getMapField(collector, "counters");
        Map<String, ?> gauges = getMapField(collector, "gauges");
        Map<String, ?> histograms = getMapField(collector, "histograms");

        assertTrue(counters.isEmpty());
        assertTrue(gauges.isEmpty());
        assertTrue(histograms.isEmpty());
    }

    @Test
    public void testGetMetricAndGetMetrics() {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("ns_" + UUID.randomUUID().toString().replace("-", ""));

        collector.incrementCounter("requests.total", 2);
        Metric counter = collector.getMetric("requests.total");
        assertNotNull(counter);
        assertEquals(MetricType.COUNTER, counter.getType());
        assertEquals(2.0, counter.getValue());

        Map<String, Metric> all = collector.getMetrics();
        assertTrue(all.containsKey("requests.total"));
    }

    @Test
    public void testTaggedMetricsAreReadable() {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("ns_" + UUID.randomUUID().toString().replace("-", ""));

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("service", "a");
        tags.put("region", "b");

        collector.incrementCounter("tagged.counter", tags);
        collector.setGauge("tagged.gauge", 1.5, tags);

        Metric taggedCounter = collector.getMetric("tagged.counter.region_b.service_a");
        assertNotNull(taggedCounter);
        assertEquals(MetricType.COUNTER, taggedCounter.getType());
        assertEquals(tags, taggedCounter.getTags());

        Metric taggedGauge = collector.getMetric("tagged.gauge.region_b.service_a");
        assertNotNull(taggedGauge);
        assertEquals(MetricType.GAUGE, taggedGauge.getType());
        assertEquals(1.5, taggedGauge.getValue());
        assertEquals(tags, taggedGauge.getTags());
    }

    @Test
    public void testClearUnregistersCollectorsSoTheyCanBeRecreated() {
        String namespace = "ns_" + UUID.randomUUID().toString().replace("-", "");

        PrometheusMetricCollector first = new PrometheusMetricCollector(namespace);
        first.incrementCounter("recreate.me");
        first.clear();

        PrometheusMetricCollector second = new PrometheusMetricCollector(namespace);
        second.incrementCounter("recreate.me");
        second.clear();
    }

    @Test
    public void testCrossTypeNameFailsFastWithClearMessage() throws Exception {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("ns_" + UUID.randomUUID().toString().replace("-", ""));

        collector.incrementCounter("shared.name", 1);

        // A different collector type under the same name used to surface as simpleclient's
        // "Collector already registered" from deep inside the registry (stranding the first
        // collector forever); now it is a clear IAE naming the metric
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> collector.setGauge("shared.name", 1.0));
        assertTrue(ex.getMessage().contains("shared.name"), ex.getMessage());

        // Same-type reuse of the name stays fine
        collector.incrementCounter("shared.name", 1);
    }

    @Test
    public void testCollectorsRegisterUnderSanitizedNameNotTypePrefixedKey() throws Exception {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("ns_" + UUID.randomUUID().toString().replace("-", ""));

        collector.incrementCounter("plain.name", 1);

        Map<String, ?> counters = getMapField(collector, "counters");
        // The internal map key is the sanitized name (without type prefix).
        assertTrue(counters.containsKey("plain_name"), "map keys: " + counters.keySet());
        // Type prefix protection: registering a different type under the same name should fail
        assertThrows(IllegalArgumentException.class, () -> collector.setGauge("plain.name", 1.0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> getMapField(PrometheusMetricCollector collector, String fieldName) throws Exception {
        Field field = PrometheusMetricCollector.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, ?>) field.get(collector);
    }
}
