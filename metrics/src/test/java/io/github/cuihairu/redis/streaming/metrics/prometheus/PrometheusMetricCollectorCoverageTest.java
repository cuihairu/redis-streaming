package io.github.cuihairu.redis.streaming.metrics.prometheus;

import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers PrometheusMetricCollector ctor overloads and tagged metric helpers. */
class PrometheusMetricCollectorCoverageTest {

    @Test
    void ctorOverloadsAreUsable() {
        assertNotNull(new PrometheusMetricCollector());
        assertNotNull(new PrometheusMetricCollector("other"));
        CollectorRegistry registry = new CollectorRegistry();
        PrometheusMetricCollector collector = new PrometheusMetricCollector("testns", registry);
        assertNotNull(collector);

        collector.incrementCounter("hits", 2);
        collector.setGauge("temp", 36.5);
        collector.recordHistogram("latency", 12.5);
        collector.markMeter("marks");
        collector.recordTimer("op", 250);
        assertNotNull(collector.getMetric("hits"));
        assertTrue(collector.getMetrics().containsKey("marks"), "markMeter uses getCounterValue");
    }

    @Test
    void taggedMetricsAlignLabelSchema() {
        CollectorRegistry registry = new CollectorRegistry();
        PrometheusMetricCollector collector = new PrometheusMetricCollector("lbl", registry);

        collector.incrementCounter("c1", Map.of("a", "1", "b", "2"));
        collector.incrementCounter("c1", Map.of("b", "3", "a", "4")); // same schema, different order
        collector.setGauge("g1", 1.5, Map.of("x", "10"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> collector.incrementCounter("c1", Map.of("a", "9")), "label schema mismatch is rejected");
        assertTrue(collector.getMetrics().size() >= 3);
    }

    @Test
    void clearUnregistersCollectors() {
        CollectorRegistry registry = new CollectorRegistry();
        PrometheusMetricCollector collector = new PrometheusMetricCollector("clr", registry);
        collector.incrementCounter("c", Map.of("k", "v"));
        collector.setGauge("g", 1.0, Map.of("k", "v"));
        collector.clear();
        assertTrue(collector.getMetrics().isEmpty());
    }
}
