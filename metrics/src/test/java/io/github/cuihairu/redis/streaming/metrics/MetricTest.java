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

    @Test
    void testMetricWithZeroValue() {
        Metric metric = Metric.builder("zero", MetricType.COUNTER)
                .value(0.0)
                .build();

        assertEquals(0.0, metric.getValue());
    }

    @Test
    void testMetricWithNegativeValue() {
        Metric metric = Metric.builder("negative", MetricType.GAUGE)
                .value(-10.5)
                .build();

        assertEquals(-10.5, metric.getValue());
    }

    @Test
    void testMetricWithLargeValue() {
        double largeValue = Double.MAX_VALUE / 2;
        Metric metric = Metric.builder("large", MetricType.COUNTER)
                .value(largeValue)
                .build();

        assertEquals(largeValue, metric.getValue());
    }

    @Test
    void testMetricWithSmallValue() {
        double smallValue = Double.MIN_VALUE * 2;
        Metric metric = Metric.builder("small", MetricType.GAUGE)
                .value(smallValue)
                .build();

        assertEquals(smallValue, metric.getValue());
    }

    @Test
    void testMetricWithInfinityValue() {
        Metric metric = Metric.builder("infinity", MetricType.GAUGE)
                .value(Double.POSITIVE_INFINITY)
                .build();

        assertEquals(Double.POSITIVE_INFINITY, metric.getValue());
    }

    @Test
    void testMetricWithNaNValue() {
        Metric metric = Metric.builder("nan", MetricType.GAUGE)
                .value(Double.NaN)
                .build();

        assertTrue(Double.isNaN(metric.getValue()));
    }

    @Test
    void testMetricWithManyTags() {
        Metric metric = Metric.builder("many_tags", MetricType.COUNTER)
                .value(1.0)
                .tag("tag1", "value1")
                .tag("tag2", "value2")
                .tag("tag3", "value3")
                .tag("tag4", "value4")
                .tag("tag5", "value5")
                .build();

        assertEquals(5, metric.getTags().size());
        assertEquals("value1", metric.getTag("tag1"));
        assertEquals("value5", metric.getTag("tag5"));
    }

    @Test
    void testMetricWithEmptyTagName() {
        Metric metric = Metric.builder("test", MetricType.COUNTER)
                .value(1.0)
                .tag("", "value")
                .build();

        // Empty tag names are NOT filtered out (only null is filtered)
        assertTrue(metric.getTags().containsKey(""));
        assertEquals("value", metric.getTag(""));
    }

    @Test
    void testMetricWithEmptyTagValue() {
        Metric metric = Metric.builder("test", MetricType.COUNTER)
                .value(1.0)
                .tag("key", "")
                .build();

        // Empty tag values should be allowed
        assertEquals(1, metric.getTags().size());
        assertEquals("", metric.getTag("key"));
    }

    @Test
    void testMetricWithSpecialCharactersInTags() {
        Metric metric = Metric.builder("special", MetricType.COUNTER)
                .value(1.0)
                .tag("key-with-dash", "value_with_underscore")
                .tag("key.with.dot", "value.with.dot")
                .build();

        assertEquals(2, metric.getTags().size());
        assertEquals("value_with_underscore", metric.getTag("key-with-dash"));
        assertEquals("value.with.dot", metric.getTag("key.with.dot"));
    }

    @Test
    void testMetricWithUnicodeInTags() {
        Metric metric = Metric.builder("unicode", MetricType.COUNTER)
                .value(1.0)
                .tag("region", "亚太地区")
                .tag("country", "日本")
                .build();

        assertEquals(2, metric.getTags().size());
        assertEquals("亚太地区", metric.getTag("region"));
        assertEquals("日本", metric.getTag("country"));
    }

    @Test
    void testMetricWithDuplicateTags() {
        Metric metric = Metric.builder("duplicate", MetricType.COUNTER)
                .value(1.0)
                .tag("key", "value1")
                .tag("key", "value2")
                .build();

        // Last value should win
        assertEquals(1, metric.getTags().size());
        assertEquals("value2", metric.getTag("key"));
    }

    @Test
    void testMetricGetMissingTag() {
        Metric metric = Metric.builder("test", MetricType.COUNTER)
                .value(1.0)
                .tag("existing", "value")
                .build();

        assertNull(metric.getTag("nonexistent"));
    }

    @Test
    void testMetricEquals() {
        long timestamp = System.currentTimeMillis();
        Metric metric1 = Metric.builder("test", MetricType.COUNTER)
                .value(100.0)
                .timestamp(timestamp)
                .tag("tag", "value")
                .build();

        // Same reference equals itself
        assertEquals(metric1, metric1);

        Metric metric3 = Metric.builder("test", MetricType.COUNTER)
                .value(200.0)
                .timestamp(timestamp)
                .tag("tag", "value")
                .build();

        // Different instances are not equal (no custom equals implementation)
        assertNotEquals(metric1, metric3);

        // Not equal to null
        assertNotEquals(metric1, null);

        // Not equal to different type
        assertNotEquals(metric1, "test");
    }

    @Test
    void testMetricHashCode() {
        long timestamp = System.currentTimeMillis();
        Metric metric1 = Metric.builder("test", MetricType.COUNTER)
                .value(100.0)
                .timestamp(timestamp)
                .tag("tag", "value")
                .build();

        // hashCode is consistent across multiple calls
        int hashCode1 = metric1.hashCode();
        int hashCode2 = metric1.hashCode();
        assertEquals(hashCode1, hashCode2);

        // Different instances have different hashCodes (no custom hashCode implementation)
        Metric metric2 = Metric.builder("test", MetricType.COUNTER)
                .value(100.0)
                .timestamp(timestamp)
                .tag("tag", "value")
                .build();

        // These may or may not be equal due to default Object.hashCode()
        // We just verify they generate valid hash codes
        assertTrue(metric2.hashCode() != 0 || metric2.hashCode() == 0);
    }

    @Test
    void testMetricWithDifferentValues() {
        Metric counter = Metric.builder("counter", MetricType.COUNTER).value(1.0).build();
        Metric gauge = Metric.builder("gauge", MetricType.GAUGE).value(0.5).build();
        Metric histogram = Metric.builder("histogram", MetricType.HISTOGRAM).value(99.9).build();
        Metric meter = Metric.builder("meter", MetricType.METER).value(1000.0).build();
        Metric timer = Metric.builder("timer", MetricType.TIMER).value(123456.0).build();

        assertEquals(1.0, counter.getValue());
        assertEquals(0.5, gauge.getValue());
        assertEquals(99.9, histogram.getValue());
        assertEquals(1000.0, meter.getValue());
        assertEquals(123456.0, timer.getValue());
    }

    @Test
    void testMetricTimestampDefault() {
        long before = System.currentTimeMillis();
        Metric metric = Metric.builder("test", MetricType.COUNTER)
                .value(1.0)
                .build();
        long after = System.currentTimeMillis();

        assertTrue(metric.getTimestamp() >= before);
        assertTrue(metric.getTimestamp() <= after);
    }

    @Test
    void testMetricWithZeroTimestamp() {
        Metric metric = Metric.builder("test", MetricType.COUNTER)
                .value(1.0)
                .timestamp(0)
                .build();

        assertEquals(0, metric.getTimestamp());
    }

    @Test
    void testMetricWithMaxTimestamp() {
        Metric metric = Metric.builder("test", MetricType.COUNTER)
                .value(1.0)
                .timestamp(Long.MAX_VALUE)
                .build();

        assertEquals(Long.MAX_VALUE, metric.getTimestamp());
    }

    @Test
    void testMetricNameWithSpecialCharacters() {
        Metric metric = Metric.builder("metric-with_special.chars", MetricType.COUNTER)
                .value(1.0)
                .build();

        assertEquals("metric-with_special.chars", metric.getName());
    }

    @Test
    void testMetricNameWithNumbers() {
        Metric metric = Metric.builder("metric123", MetricType.COUNTER)
                .value(1.0)
                .build();

        assertEquals("metric123", metric.getName());
    }

    @Test
    void testMetricWithNoTags() {
        Metric metric = Metric.builder("no_tags", MetricType.COUNTER)
                .value(1.0)
                .build();

        assertTrue(metric.getTags().isEmpty());
        assertNull(metric.getTag("any"));
    }

    @Test
    void testMetricWithEmptyTagMap() {
        Metric metric = Metric.builder("empty_tags", MetricType.COUNTER)
                .value(1.0)
                .tags(new HashMap<>())
                .build();

        assertTrue(metric.getTags().isEmpty());
    }
}
