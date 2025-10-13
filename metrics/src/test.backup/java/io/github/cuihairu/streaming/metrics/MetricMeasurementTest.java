package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;

class MetricMeasurementTest {

    @Test
    void testConstructor() {
        Map<String, String> tags = Map.of("env", "test", "service", "api");
        Instant timestamp = Instant.now();

        MetricMeasurement measurement = new MetricMeasurement(
                "http.requests",
                MetricMeasurement.Type.COUNTER,
                42.0,
                tags,
                timestamp,
                "test-collector"
        );

        assertEquals("http.requests", measurement.getName());
        assertEquals(MetricMeasurement.Type.COUNTER, measurement.getType());
        assertEquals(42.0, measurement.getValue());
        assertEquals(tags, measurement.getTags());
        assertEquals(timestamp, measurement.getTimestamp());
        assertEquals("test-collector", measurement.getCollectorName());
    }

    @Test
    void testConstructorWithoutTimestamp() {
        Map<String, String> tags = Map.of("env", "test");

        MetricMeasurement measurement = new MetricMeasurement(
                "test.metric",
                MetricMeasurement.Type.GAUGE,
                10.5,
                tags,
                "collector"
        );

        assertEquals("test.metric", measurement.getName());
        assertEquals(MetricMeasurement.Type.GAUGE, measurement.getType());
        assertEquals(10.5, measurement.getValue());
        assertEquals(tags, measurement.getTags());
        assertNotNull(measurement.getTimestamp());
        assertEquals("collector", measurement.getCollectorName());
    }

    @Test
    void testFactoryMethods() {
        Map<String, String> tags = Map.of("key", "value");

        MetricMeasurement counter = MetricMeasurement.counter("counter.metric", 5.0, tags, "collector");
        assertEquals(MetricMeasurement.Type.COUNTER, counter.getType());
        assertEquals(5.0, counter.getValue());

        MetricMeasurement gauge = MetricMeasurement.gauge("gauge.metric", 3.14, tags, "collector");
        assertEquals(MetricMeasurement.Type.GAUGE, gauge.getType());
        assertEquals(3.14, gauge.getValue());

        MetricMeasurement timer = MetricMeasurement.timer("timer.metric", 1000L, tags, "collector");
        assertEquals(MetricMeasurement.Type.TIMER, timer.getType());
        assertEquals(1000.0, timer.getValue());

        MetricMeasurement histogram = MetricMeasurement.histogram("histogram.metric", 7.5, tags, "collector");
        assertEquals(MetricMeasurement.Type.HISTOGRAM, histogram.getType());
        assertEquals(7.5, histogram.getValue());
    }

    @Test
    void testGetFullNameWithoutTags() {
        MetricMeasurement measurement = new MetricMeasurement(
                "simple.metric",
                MetricMeasurement.Type.COUNTER,
                1.0,
                null,
                "collector"
        );

        assertEquals("simple.metric", measurement.getFullName());
        assertFalse(measurement.hasTags());
    }

    @Test
    void testGetFullNameWithEmptyTags() {
        MetricMeasurement measurement = new MetricMeasurement(
                "simple.metric",
                MetricMeasurement.Type.COUNTER,
                1.0,
                Map.of(),
                "collector"
        );

        assertEquals("simple.metric", measurement.getFullName());
        assertFalse(measurement.hasTags());
    }

    @Test
    void testGetFullNameWithTags() {
        Map<String, String> tags = Map.of("env", "prod", "service", "api", "region", "us-east-1");
        MetricMeasurement measurement = new MetricMeasurement(
                "http.requests",
                MetricMeasurement.Type.COUNTER,
                100.0,
                tags,
                "collector"
        );

        String fullName = measurement.getFullName();
        assertTrue(fullName.startsWith("http.requests{"));
        assertTrue(fullName.endsWith("}"));
        assertTrue(fullName.contains("env=prod"));
        assertTrue(fullName.contains("service=api"));
        assertTrue(fullName.contains("region=us-east-1"));
        assertTrue(measurement.hasTags());
    }

    @Test
    void testGetTag() {
        Map<String, String> tags = Map.of("env", "test", "service", "api");
        MetricMeasurement measurement = new MetricMeasurement(
                "test.metric",
                MetricMeasurement.Type.GAUGE,
                1.0,
                tags,
                "collector"
        );

        assertEquals("test", measurement.getTag("env"));
        assertEquals("api", measurement.getTag("service"));
        assertNull(measurement.getTag("nonexistent"));
    }

    @Test
    void testGetTagWithNullTags() {
        MetricMeasurement measurement = new MetricMeasurement(
                "test.metric",
                MetricMeasurement.Type.GAUGE,
                1.0,
                null,
                "collector"
        );

        assertNull(measurement.getTag("any"));
    }

    @Test
    void testTypeEnum() {
        assertEquals(4, MetricMeasurement.Type.values().length);
        assertNotNull(MetricMeasurement.Type.valueOf("COUNTER"));
        assertNotNull(MetricMeasurement.Type.valueOf("GAUGE"));
        assertNotNull(MetricMeasurement.Type.valueOf("TIMER"));
        assertNotNull(MetricMeasurement.Type.valueOf("HISTOGRAM"));
    }

    @Test
    void testToString() {
        Map<String, String> tags = Map.of("env", "test");
        MetricMeasurement measurement = new MetricMeasurement(
                "test.metric",
                MetricMeasurement.Type.COUNTER,
                42.0,
                tags,
                "test-collector"
        );

        String result = measurement.toString();
        assertTrue(result.contains("test.metric"));
        assertTrue(result.contains("COUNTER"));
        assertTrue(result.contains("42.00"));
        assertTrue(result.contains("test-collector"));
        assertTrue(result.contains("env=test"));
    }
}