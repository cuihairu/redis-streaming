package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricTimerTest {

    private InMemoryMetricCollector collector;

    @BeforeEach
    void setUp() {
        collector = new InMemoryMetricCollector();
    }

    @Test
    void testBasicTiming() throws InterruptedException {
        MetricTimer timer = MetricTimer.start("test_timer", collector);
        Thread.sleep(50); // Sleep for at least 50ms
        long duration = timer.stop();

        assertTrue(duration >= 50);
        Metric metric = collector.getMetric("test_timer");
        assertNotNull(metric);
        assertEquals(MetricType.TIMER, metric.getType());
        assertTrue(metric.getValue() >= 50);
    }

    @Test
    void testAutoCloseable() throws InterruptedException {
        try (MetricTimer timer = MetricTimer.start("autocloseable_timer", collector)) {
            Thread.sleep(30);
        }

        Metric metric = collector.getMetric("autocloseable_timer");
        assertNotNull(metric);
        assertTrue(metric.getValue() >= 30);
    }

    @Test
    void testTimeRunnable() throws InterruptedException {
        MetricTimer.time("runnable_timer", collector, () -> {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Metric metric = collector.getMetric("runnable_timer");
        assertNotNull(metric);
        assertTrue(metric.getValue() >= 40);
    }

    @Test
    void testTimeCallable() throws Exception {
        String result = MetricTimer.time("callable_timer", collector, () -> {
            Thread.sleep(25);
            return "success";
        });

        assertEquals("success", result);
        Metric metric = collector.getMetric("callable_timer");
        assertNotNull(metric);
        assertTrue(metric.getValue() >= 25);
    }

    @Test
    void testTimerWithNullCollector() {
        MetricTimer timer = MetricTimer.start("null_collector_timer", null);
        long duration = timer.stop();

        assertTrue(duration >= 0);
        // Should not throw exception
    }

    @Test
    void testMultipleTimers() throws InterruptedException {
        try (MetricTimer timer1 = MetricTimer.start("timer1", collector)) {
            Thread.sleep(20);
        }

        try (MetricTimer timer2 = MetricTimer.start("timer2", collector)) {
            Thread.sleep(30);
        }

        Metric metric1 = collector.getMetric("timer1");
        Metric metric2 = collector.getMetric("timer2");

        assertNotNull(metric1);
        assertNotNull(metric2);
        assertTrue(metric1.getValue() >= 20);
        assertTrue(metric2.getValue() >= 30);
    }

    @Test
    void testTimerAccuracy() throws InterruptedException {
        long startTime = System.currentTimeMillis();
        try (MetricTimer timer = MetricTimer.start("accuracy_test", collector)) {
            Thread.sleep(100);
        }
        long endTime = System.currentTimeMillis();
        long actualDuration = endTime - startTime;

        Metric metric = collector.getMetric("accuracy_test");
        assertNotNull(metric);

        // Timer should be within 10ms of actual duration
        assertTrue(Math.abs(metric.getValue() - actualDuration) < 10);
    }
}
