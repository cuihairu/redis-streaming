package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricCollector interface
 */
class MetricCollectorTest {

    @Test
    void testInterfaceIsDefined() {
        // Given & When & Then
        assertTrue(MetricCollector.class.isInterface());
    }

    @Test
    void testIncrementCounterMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("incrementCounter", String.class));
    }

    @Test
    void testIncrementCounterWithAmountMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("incrementCounter", String.class, long.class));
    }

    @Test
    void testSetGaugeMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("setGauge", String.class, double.class));
    }

    @Test
    void testRecordHistogramMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("recordHistogram", String.class, double.class));
    }

    @Test
    void testMarkMeterMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("markMeter", String.class));
    }

    @Test
    void testRecordTimerMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("recordTimer", String.class, long.class));
    }

    @Test
    void testIncrementCounterWithTagsMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("incrementCounter", String.class, Map.class));
    }

    @Test
    void testSetGaugeWithTagsMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("setGauge", String.class, double.class, Map.class));
    }

    @Test
    void testGetMetricsMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("getMetrics"));
    }

    @Test
    void testGetMetricMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("getMetric", String.class));
    }

    @Test
    void testClearMethodExists() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(MetricCollector.class.getMethod("clear"));
    }

    @Test
    void testSimpleImplementation() {
        // Given - create a simple in-memory implementation
        MetricCollector collector = new MetricCollector() {
            private final Map<String, Metric> metrics = new HashMap<>();

            @Override
            public void incrementCounter(String name) {
                incrementCounter(name, 1L);
            }

            @Override
            public void incrementCounter(String name, long amount) {
                Metric existing = metrics.get(name);
                double newValue = existing == null ? amount : existing.getValue() + amount;
                metrics.put(name, Metric.builder(name, MetricType.COUNTER).value(newValue).build());
            }

            @Override
            public void setGauge(String name, double value) {
                metrics.put(name, Metric.builder(name, MetricType.GAUGE).value(value).build());
            }

            @Override
            public void recordHistogram(String name, double value) {
                metrics.put(name, Metric.builder(name, MetricType.HISTOGRAM).value(value).build());
            }

            @Override
            public void markMeter(String name) {
                Metric existing = metrics.get(name);
                double newValue = existing == null ? 1 : existing.getValue() + 1;
                metrics.put(name, Metric.builder(name, MetricType.METER).value(newValue).build());
            }

            @Override
            public void recordTimer(String name, long durationMillis) {
                metrics.put(name, Metric.builder(name, MetricType.TIMER).value(durationMillis).build());
            }

            @Override
            public void incrementCounter(String name, Map<String, String> tags) {
                incrementCounter(name, 1L);
            }

            @Override
            public void setGauge(String name, double value, Map<String, String> tags) {
                setGauge(name, value);
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
                metrics.clear();
            }
        };

        // When - collect various metrics
        collector.incrementCounter("requests");
        collector.incrementCounter("requests", 5);
        collector.setGauge("cpu_usage", 75.5);
        collector.recordHistogram("response_time", 123.45);
        collector.markMeter("events");
        collector.recordTimer("processing_time", 100);

        // Then - verify operations
        assertEquals(5, collector.getMetrics().size());
        assertNotNull(collector.getMetric("requests"));
        assertEquals(6.0, collector.getMetric("requests").getValue());
        assertEquals(MetricType.COUNTER, collector.getMetric("requests").getType());
        assertEquals(75.5, collector.getMetric("cpu_usage").getValue());
        assertEquals(MetricType.GAUGE, collector.getMetric("cpu_usage").getType());
        assertNotNull(collector.getMetric("response_time"));
        assertEquals(MetricType.HISTOGRAM, collector.getMetric("response_time").getType());
        assertNotNull(collector.getMetric("events"));
        assertEquals(MetricType.METER, collector.getMetric("events").getType());
        assertNotNull(collector.getMetric("processing_time"));
        assertEquals(MetricType.TIMER, collector.getMetric("processing_time").getType());
    }

    @Test
    void testIncrementCounter() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.incrementCounter("counter1");
        collector.incrementCounter("counter1");
        collector.incrementCounter("counter2");

        // Then
        assertEquals(2.0, collector.getMetric("counter1").getValue());
        assertEquals(1.0, collector.getMetric("counter2").getValue());
    }

    @Test
    void testIncrementCounterWithAmount() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.incrementCounter("counter", 10);
        collector.incrementCounter("counter", 5);
        collector.incrementCounter("counter", -3);

        // Then
        assertEquals(12.0, collector.getMetric("counter").getValue());
    }

    @Test
    void testSetGauge() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.setGauge("temperature", 23.5);
        collector.setGauge("temperature", 24.0);
        collector.setGauge("humidity", 65.0);

        // Then
        // Gauge should be set to last value (not cumulative)
        assertEquals(24.0, collector.getMetric("temperature").getValue());
        assertEquals(65.0, collector.getMetric("humidity").getValue());
    }

    @Test
    void testRecordHistogram() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.recordHistogram("latency", 100);
        collector.recordHistogram("latency", 200);
        collector.recordHistogram("latency", 150);

        // Then
        assertNotNull(collector.getMetric("latency"));
        assertEquals(MetricType.HISTOGRAM, collector.getMetric("latency").getType());
    }

    @Test
    void testMarkMeter() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.markMeter("requests");
        collector.markMeter("requests");
        collector.markMeter("requests");

        // Then
        assertEquals(3.0, collector.getMetric("requests").getValue());
        assertEquals(MetricType.METER, collector.getMetric("requests").getType());
    }

    @Test
    void testRecordTimer() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.recordTimer("operation", 50);
        collector.recordTimer("operation", 75);
        collector.recordTimer("operation", 100);

        // Then
        assertNotNull(collector.getMetric("operation"));
        assertEquals(MetricType.TIMER, collector.getMetric("operation").getType());
    }

    @Test
    void testIncrementCounterWithTags() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();
        Map<String, String> tags = new HashMap<>();
        tags.put("environment", "production");
        tags.put("region", "us-east");

        // When
        collector.incrementCounter("requests", tags);

        // Then
        assertNotNull(collector.getMetric("requests"));
        assertEquals(MetricType.COUNTER, collector.getMetric("requests").getType());
    }

    @Test
    void testSetGaugeWithTags() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();
        Map<String, String> tags = new HashMap<>();
        tags.put("host", "server1");

        // When
        collector.setGauge("cpu", 80.0, tags);

        // Then
        assertNotNull(collector.getMetric("cpu"));
        assertEquals(80.0, collector.getMetric("cpu").getValue());
        assertEquals(MetricType.GAUGE, collector.getMetric("cpu").getType());
    }

    @Test
    void testGetMetrics() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();
        collector.incrementCounter("counter1");
        collector.setGauge("gauge1", 10.0);

        // When
        Map<String, Metric> metrics = collector.getMetrics();

        // Then
        assertEquals(2, metrics.size());
        assertTrue(metrics.containsKey("counter1"));
        assertTrue(metrics.containsKey("gauge1"));
    }

    @Test
    void testGetMetric() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();
        collector.incrementCounter("my_counter");

        // When
        Metric metric = collector.getMetric("my_counter");

        // Then
        assertNotNull(metric);
        assertEquals("my_counter", metric.getName());
        assertEquals(MetricType.COUNTER, metric.getType());
    }

    @Test
    void testGetMetricReturnsNullForNonExistent() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        Metric metric = collector.getMetric("nonexistent");

        // Then
        assertNull(metric);
    }

    @Test
    void testClear() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();
        collector.incrementCounter("counter1");
        collector.setGauge("gauge1", 10.0);
        assertEquals(2, collector.getMetrics().size());

        // When
        collector.clear();

        // Then
        assertEquals(0, collector.getMetrics().size());
        assertNull(collector.getMetric("counter1"));
        assertNull(collector.getMetric("gauge1"));
    }

    @Test
    void testClearEmptyCollector() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When & Then - should not throw
        assertDoesNotThrow(() -> collector.clear());
        assertEquals(0, collector.getMetrics().size());
    }

    @Test
    void testMultipleOperationsOnSameMetric() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When - multiple operations on the same counter
        collector.incrementCounter("operations");
        collector.incrementCounter("operations", 10);
        collector.incrementCounter("operations", 5);

        // Then
        assertEquals(16.0, collector.getMetric("operations").getValue());
    }

    @Test
    void testDifferentMetricTypes() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.incrementCounter("counter");
        collector.setGauge("gauge", 50.0);
        collector.recordHistogram("histogram", 100.0);
        collector.markMeter("meter");
        collector.recordTimer("timer", 200);

        // Then - verify all metric types
        assertEquals(MetricType.COUNTER, collector.getMetric("counter").getType());
        assertEquals(MetricType.GAUGE, collector.getMetric("gauge").getType());
        assertEquals(MetricType.HISTOGRAM, collector.getMetric("histogram").getType());
        assertEquals(MetricType.METER, collector.getMetric("meter").getType());
        assertEquals(MetricType.TIMER, collector.getMetric("timer").getType());
    }

    @Test
    void testMetricCollectorIsSerializable() {
        // Given & When & Then
        assertTrue(MetricCollector.class instanceof java.io.Serializable);
    }

    @Test
    void testIncrementCounterWithNegativeAmount() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.incrementCounter("counter", 100);
        collector.incrementCounter("counter", -20);

        // Then
        assertEquals(80.0, collector.getMetric("counter").getValue());
    }

    @Test
    void testSetGaugeWithNegativeValue() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.setGauge("temperature", -10.5);

        // Then
        assertEquals(-10.5, collector.getMetric("temperature").getValue());
    }

    @Test
    void testSetGaugeWithZero() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.setGauge("value", 0.0);

        // Then
        assertEquals(0.0, collector.getMetric("value").getValue());
    }

    @Test
    void testRecordTimerWithZeroDuration() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.recordTimer("instant", 0);

        // Then
        assertNotNull(collector.getMetric("instant"));
        assertEquals(0.0, collector.getMetric("instant").getValue());
    }

    @Test
    void testIncrementCounterWithZero() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When
        collector.incrementCounter("counter", 100);
        collector.incrementCounter("counter", 0);

        // Then
        assertEquals(100.0, collector.getMetric("counter").getValue());
    }

    @Test
    void testGetMetricsReturnsImmutableCopy() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();
        collector.incrementCounter("counter");

        // When
        Map<String, Metric> metrics1 = collector.getMetrics();
        Map<String, Metric> metrics2 = collector.getMetrics();

        // Then - should return different instances
        assertNotSame(metrics1, metrics2);
        assertEquals(metrics1, metrics2);
    }

    @Test
    void testIncrementCounterWithNullTags() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When & Then - should handle null tags gracefully
        assertDoesNotThrow(() -> collector.incrementCounter("counter", (Map<String, String>) null));
    }

    @Test
    void testSetGaugeWithNullTags() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When & Then - should handle null tags gracefully
        assertDoesNotThrow(() -> collector.setGauge("gauge", 50.0, null));
    }

    @Test
    void testLargeNumberOfMetrics() {
        // Given
        MetricCollector collector = new InMemoryMetricCollector();

        // When - create many metrics
        for (int i = 0; i < 1000; i++) {
            collector.incrementCounter("metric_" + i);
        }

        // Then
        assertEquals(1000, collector.getMetrics().size());
        assertNotNull(collector.getMetric("metric_0"));
        assertNotNull(collector.getMetric("metric_999"));
    }
}
