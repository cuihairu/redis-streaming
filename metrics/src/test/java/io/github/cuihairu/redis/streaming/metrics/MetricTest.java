package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricTest {

    @Test
    void testBasicMetric() {
        Metric metric = Metric.builder("test_metric", MetricType.COUNTER)
                .value(100.0)
                .build();

        assertEquals("test_metric", metric.getName());
        assertEquals(MetricType.COUNTER, metric.getType());
        assertEquals(100.0, metric.getValue());
        assertTrue(metric.getTimestamp() > 0);
    }

    @Test
    void testMetricWithTags() {
        Map<String, String> tags = new HashMap<>();
        tags.put("environment", "production");
        tags.put("region", "us-east");

        Metric metric = Metric.builder("requests", MetricType.COUNTER)
                .value(50.0)
                .tags(tags)
                .build();

        assertEquals("requests", metric.getName());
        assertEquals(2, metric.getTags().size());
        assertEquals("production", metric.getTag("environment"));
        assertEquals("us-east", metric.getTag("region"));
    }

    @Test
    void testMetricWithSingleTag() {
        Metric metric = Metric.builder("cpu_usage", MetricType.GAUGE)
                .value(75.5)
                .tag("host", "server1")
                .tag("datacenter", "dc1")
                .build();

        assertEquals(2, metric.getTags().size());
        assertEquals("server1", metric.getTag("host"));
        assertEquals("dc1", metric.getTag("datacenter"));
    }

    @Test
    void testMetricWithCustomTimestamp() {
        long customTime = 1609459200000L; // 2021-01-01 00:00:00 UTC

        Metric metric = Metric.builder("event", MetricType.COUNTER)
                .value(1.0)
                .timestamp(customTime)
                .build();

        assertEquals(customTime, metric.getTimestamp());
    }

    @Test
    void testMetricWithNullTag() {
        Metric metric = Metric.builder("test", MetricType.COUNTER)
                .value(1.0)
                .tag(null, "value")
                .tag("key", null)
                .build();

        assertTrue(metric.getTags().isEmpty());
    }

    @Test
    void testMetricImmutability() {
        Map<String, String> originalTags = new HashMap<>();
        originalTags.put("key", "value");

        Metric metric = Metric.builder("test", MetricType.COUNTER)
                .value(1.0)
                .tags(originalTags)
                .build();

        // Modify original tags
        originalTags.put("newkey", "newvalue");

        // Metric tags should not be affected
        assertEquals(1, metric.getTags().size());
        assertNull(metric.getTag("newkey"));

        // Modify returned tags
        Map<String, String> returnedTags = metric.getTags();
        returnedTags.put("anotherkey", "anothervalue");

        // Metric should still have original tags only
        assertEquals(1, metric.getTags().size());
    }

    @Test
    void testAllMetricTypes() {
        for (MetricType type : MetricType.values()) {
            Metric metric = Metric.builder("test_" + type, type)
                    .value(42.0)
                    .build();

            assertEquals(type, metric.getType());
        }
    }

    @Test
    void testMetricToString() {
        Metric metric = Metric.builder("test", MetricType.GAUGE)
                .value(123.45)
                .tag("env", "test")
                .build();

        String str = metric.toString();
        assertTrue(str.contains("test"));
        assertTrue(str.contains("GAUGE"));
        assertTrue(str.contains("123.45"));
    }

    @Test
    void testBuilderValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                Metric.builder(null, MetricType.COUNTER)
        );

        assertThrows(IllegalArgumentException.class, () ->
                Metric.builder("", MetricType.COUNTER)
        );

        assertThrows(IllegalArgumentException.class, () ->
                Metric.builder("test", null)
        );
    }
}
