package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryMetricCollectorTest {

    private InMemoryMetricCollector collector;

    @BeforeEach
    void setUp() {
        collector = new InMemoryMetricCollector();
    }

    @Test
    void testIncrementCounter() {
        collector.incrementCounter("requests");
        assertEquals(1, collector.getCounterValue("requests"));

        collector.incrementCounter("requests");
        assertEquals(2, collector.getCounterValue("requests"));

        collector.incrementCounter("requests", 5L);
        assertEquals(7, collector.getCounterValue("requests"));
    }

    @Test
    void testSetGauge() {
        collector.setGauge("temperature", 25.5);
        assertEquals(25.5, collector.getGaugeValue("temperature"));

        collector.setGauge("temperature", 30.0);
        assertEquals(30.0, collector.getGaugeValue("temperature"));
    }

    @Test
    void testRecordHistogram() {
        collector.recordHistogram("response_time", 100.0);
        Metric metric = collector.getMetric("response_time");

        assertNotNull(metric);
        assertEquals("response_time", metric.getName());
        assertEquals(MetricType.HISTOGRAM, metric.getType());
        assertEquals(100.0, metric.getValue());
    }

    @Test
    void testMarkMeter() {
        collector.markMeter("events");
        Metric metric = collector.getMetric("events");

        assertNotNull(metric);
        assertEquals("events", metric.getName());
        assertEquals(MetricType.METER, metric.getType());
        assertTrue(metric.getValue() > 0);
    }

    @Test
    void testRecordTimer() {
        collector.recordTimer("process_time", 500L);
        Metric metric = collector.getMetric("process_time");

        assertNotNull(metric);
        assertEquals("process_time", metric.getName());
        assertEquals(MetricType.TIMER, metric.getType());
        assertEquals(500.0, metric.getValue());
    }

    @Test
    void testCounterWithTags() {
        Map<String, String> tags = new HashMap<>();
        tags.put("region", "us-west");
        tags.put("env", "prod");

        collector.incrementCounter("requests", tags);

        // Base counter should still be incremented
        assertEquals(1, collector.getCounterValue("requests"));

        // Tagged metric should exist
        Map<String, Metric> metrics = collector.getMetrics();
        assertTrue(metrics.size() >= 2); // At least base + tagged
    }

    @Test
    void testGaugeWithTags() {
        Map<String, String> tags = new HashMap<>();
        tags.put("host", "server1");

        collector.setGauge("cpu_usage", 75.5, tags);

        assertEquals(75.5, collector.getGaugeValue("cpu_usage"));

        Map<String, Metric> metrics = collector.getMetrics();
        assertTrue(metrics.size() >= 2);
    }

    @Test
    void testGetMetrics() {
        collector.incrementCounter("counter1");
        collector.setGauge("gauge1", 10.0);
        collector.recordHistogram("hist1", 5.0);

        Map<String, Metric> metrics = collector.getMetrics();
        assertTrue(metrics.size() >= 3);
    }

    @Test
    void testGetMetric() {
        collector.incrementCounter("test_counter");

        Metric metric = collector.getMetric("test_counter");
        assertNotNull(metric);
        assertEquals("test_counter", metric.getName());
        assertEquals(MetricType.COUNTER, metric.getType());

        assertNull(collector.getMetric("non_existent"));
    }

    @Test
    void testClear() {
        collector.incrementCounter("counter");
        collector.setGauge("gauge", 5.0);

        assertEquals(1, collector.getCounterValue("counter"));
        assertEquals(5.0, collector.getGaugeValue("gauge"));

        collector.clear();

        assertEquals(0, collector.getCounterValue("counter"));
        assertEquals(0.0, collector.getGaugeValue("gauge"));
        assertTrue(collector.getMetrics().isEmpty());
    }

    @Test
    void testMultipleCounters() {
        collector.incrementCounter("counter1");
        collector.incrementCounter("counter2");
        collector.incrementCounter("counter1");

        assertEquals(2, collector.getCounterValue("counter1"));
        assertEquals(1, collector.getCounterValue("counter2"));
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    collector.incrementCounter("concurrent_counter");
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(threadCount * incrementsPerThread,
                     collector.getCounterValue("concurrent_counter"));
    }
}
