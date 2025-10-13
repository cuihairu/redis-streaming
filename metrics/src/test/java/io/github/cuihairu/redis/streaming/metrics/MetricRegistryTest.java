package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetricRegistryTest {

    private MetricRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MetricRegistry();
    }

    @Test
    void testDefaultCollector() {
        MetricCollector defaultCollector = registry.getDefaultCollector();
        assertNotNull(defaultCollector);
        assertTrue(defaultCollector instanceof InMemoryMetricCollector);
    }

    @Test
    void testRegisterCollector() {
        MetricCollector customCollector = new InMemoryMetricCollector();
        registry.registerCollector("custom", customCollector);

        assertEquals(customCollector, registry.getCollector("custom"));
        assertEquals(2, registry.getCollectorCount()); // default + custom
    }

    @Test
    void testIncrementCounterInAllCollectors() {
        InMemoryMetricCollector collector1 = new InMemoryMetricCollector();
        InMemoryMetricCollector collector2 = new InMemoryMetricCollector();

        registry.registerCollector("collector1", collector1);
        registry.registerCollector("collector2", collector2);

        registry.incrementCounter("test_counter");

        assertEquals(1, collector1.getCounterValue("test_counter"));
        assertEquals(1, collector2.getCounterValue("test_counter"));
    }

    @Test
    void testSetGaugeInAllCollectors() {
        InMemoryMetricCollector collector1 = new InMemoryMetricCollector();
        InMemoryMetricCollector collector2 = new InMemoryMetricCollector();

        registry.registerCollector("collector1", collector1);
        registry.registerCollector("collector2", collector2);

        registry.setGauge("temperature", 25.5);

        assertEquals(25.5, collector1.getGaugeValue("temperature"));
        assertEquals(25.5, collector2.getGaugeValue("temperature"));
    }

    @Test
    void testRecordHistogramInAllCollectors() {
        InMemoryMetricCollector collector1 = new InMemoryMetricCollector();
        registry.registerCollector("collector1", collector1);

        registry.recordHistogram("response_time", 100.0);

        Metric metric = collector1.getMetric("response_time");
        assertNotNull(metric);
        assertEquals(100.0, metric.getValue());
    }

    @Test
    void testMarkMeterInAllCollectors() {
        InMemoryMetricCollector collector1 = new InMemoryMetricCollector();
        registry.registerCollector("collector1", collector1);

        registry.markMeter("events");

        Metric metric = collector1.getMetric("events");
        assertNotNull(metric);
        assertEquals(MetricType.METER, metric.getType());
    }

    @Test
    void testRecordTimerInAllCollectors() {
        InMemoryMetricCollector collector1 = new InMemoryMetricCollector();
        registry.registerCollector("collector1", collector1);

        registry.recordTimer("process_time", 500L);

        Metric metric = collector1.getMetric("process_time");
        assertNotNull(metric);
        assertEquals(500.0, metric.getValue());
    }

    @Test
    void testGetAllMetrics() {
        InMemoryMetricCollector collector1 = new InMemoryMetricCollector();
        InMemoryMetricCollector collector2 = new InMemoryMetricCollector();

        registry.registerCollector("collector1", collector1);
        registry.registerCollector("collector2", collector2);

        registry.incrementCounter("counter1");
        registry.setGauge("gauge1", 10.0);

        List<Metric> allMetrics = registry.getAllMetrics();
        // Each collector has 2 metrics (counter1, gauge1), plus default collector
        assertTrue(allMetrics.size() >= 6); // 3 collectors * 2 metrics
    }

    @Test
    void testClearAll() {
        InMemoryMetricCollector collector1 = (InMemoryMetricCollector) registry.getDefaultCollector();

        registry.incrementCounter("counter");
        assertEquals(1, collector1.getCounterValue("counter"));

        registry.clearAll();
        assertEquals(0, collector1.getCounterValue("counter"));
    }

    @Test
    void testGetNonExistentCollector() {
        assertNull(registry.getCollector("non_existent"));
    }

    @Test
    void testMultipleRegistrations() {
        MetricCollector collector1 = new InMemoryMetricCollector();
        MetricCollector collector2 = new InMemoryMetricCollector();

        registry.registerCollector("test", collector1);
        registry.registerCollector("test", collector2); // Overwrite

        assertEquals(collector2, registry.getCollector("test"));
    }
}
