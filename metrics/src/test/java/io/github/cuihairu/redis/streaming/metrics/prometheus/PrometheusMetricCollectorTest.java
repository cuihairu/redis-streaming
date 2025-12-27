package io.github.cuihairu.redis.streaming.metrics.prometheus;

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

        assertEquals(2, counters.size());
        assertEquals(1, gauges.size());
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

    @SuppressWarnings("unchecked")
    private static Map<String, ?> getMapField(PrometheusMetricCollector collector, String fieldName) throws Exception {
        Field field = PrometheusMetricCollector.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, ?>) field.get(collector);
    }
}
