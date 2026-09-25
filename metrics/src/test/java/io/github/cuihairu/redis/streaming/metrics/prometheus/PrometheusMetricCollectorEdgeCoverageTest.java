package io.github.cuihairu.redis.streaming.metrics.prometheus;

import io.github.cuihairu.redis.streaming.metrics.Metric;
import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers the residual {@link PrometheusMetricCollector} branches:
 * null-registry fallback, {@code markMeter} on an unknown counter and
 * label values that are present but null.
 */
class PrometheusMetricCollectorEdgeCoverageTest {

    @Test
    void nullRegistryFallsBackToTheDefaultRegistry() {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("edgeA", null);
        assertEquals("edgeA", collector.getNamespace());
        collector.incrementCounter("fallback");
        Metric metric = collector.getMetric("fallback");
        assertNotNull(metric);
        assertEquals(1.0, metric.getValue());
    }

    @Test
    void explicitRegistryIsUsedAsIs() {
        CollectorRegistry registry = new CollectorRegistry();
        PrometheusMetricCollector collector = new PrometheusMetricCollector("edgeB", registry);
        collector.incrementCounter("explicit");
        assertEquals(1.0, collector.getMetric("explicit").getValue());
    }

    @Test
    void markMeterOnUnknownCounterCreatesItWithOne() {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("edgeC", new CollectorRegistry());
        assertDoesNotThrow(() -> collector.markMeter("never-recorded"));
        Metric metric = collector.getMetric("never-recorded");
        assertNotNull(metric);
        assertEquals(1.0, metric.getValue(), "markMeter counts its own event");
    }

    @Test
    void markMeterOnRecordedCounterAddsOneToTheCurrentValue() {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("edgeD", new CollectorRegistry());
        collector.incrementCounter("hits", 4L);
        collector.markMeter("hits");
        assertEquals(5.0, collector.getMetric("hits").getValue());
    }

    @Test
    void counterValueLookupDefaultsToZeroForUnknownNames() throws Exception {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("edgeF", new CollectorRegistry());
        java.lang.reflect.Method m = PrometheusMetricCollector.class.getDeclaredMethod("getCounterValue", String.class);
        m.setAccessible(true);
        assertEquals(0L, m.invoke(collector, "absent-counter"),
                "an unknown counter must read back as 0 instead of failing");
    }

    @Test
    void nullTagValuesAreRecordedAsEmptyLabels() {
        PrometheusMetricCollector collector = new PrometheusMetricCollector("edgeE", new CollectorRegistry());
        Map<String, String> tags = new HashMap<>();
        tags.put("route", null);

        assertDoesNotThrow(() -> collector.incrementCounter("requests", tags));

        Metric metric = collector.getMetric("requests.route_null");
        assertNotNull(metric, "the tagged metric must still be tracked when a label value is null");
        assertEquals(1.0, metric.getValue());
    }
}
