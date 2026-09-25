package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code Metric.Builder#tags(Map)} with a null argument: it must be a
 * no-op instead of throwing, while a non-null map is merged into the tags.
 */
class MetricBuilderTagsCoverageTest {

    @Test
    void nullTagMapIsIgnored() {
        Metric metric = Metric.builder("m-null-tags", MetricType.COUNTER)
                .value(1.0)
                .tags(null)
                .build();
        assertTrue(metric.getTags() == null || metric.getTags().isEmpty());
    }

    @Test
    void nonNullTagMapIsMerged() {
        Metric metric = Metric.builder("m-tags", MetricType.GAUGE)
                .value(2.0)
                .tags(Map.of("region", "eu"))
                .tag("host", "h1")
                .build();
        assertEquals("eu", metric.getTag("region"));
        assertEquals("h1", metric.getTag("host"));
    }
}
