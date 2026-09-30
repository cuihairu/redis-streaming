package io.github.cuihairu.redis.streaming.metrics;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the third audit round:
 * tag-order determinism, default-collector replacement, and MetricTimer idempotence.
 */
class MetricsCollectorRegressionTest {

    @Test
    void taggedCounterIsIndependentOfTagMapIterationOrder() {
        InMemoryMetricCollector a = new InMemoryMetricCollector();
        InMemoryMetricCollector b = new InMemoryMetricCollector();

        Map<String, String> ordered = new HashMap<>();
        ordered.put("code", "200");
        ordered.put("method", "GET");
        Map<String, String> reversed = new java.util.LinkedHashMap<>();
        reversed.put("method", "GET");
        reversed.put("code", "200");

        a.incrementCounter("http.req", ordered);
        a.incrementCounter("http.req", ordered);
        b.incrementCounter("http.req", reversed);

        // one canonical series per tag set, regardless of map insertion order
        String expected = a.getMetrics().keySet().stream()
                .filter(k -> k.startsWith("http.req."))
                .findFirst().orElse(null);
        assertNotNull(expected, "the tagged series name must exist");
        assertEquals(2L, a.getCounterValue(expected));

        String reversedName = b.getMetrics().keySet().stream()
                .filter(k -> k.startsWith("http.req."))
                .findFirst().orElse(null);
        assertEquals(expected, reversedName,
                "the same tags in a different map order must land on the same series");
        assertEquals(1L, b.getCounterValue(reversedName));
    }

    @Test
    void replacingTheDefaultCollectorMovesGetDefaultCollectorWithIt() {
        MetricRegistry registry = new MetricRegistry();
        MetricCollector original = registry.getDefaultCollector();

        InMemoryMetricCollector replacement = new InMemoryMetricCollector();
        registry.registerCollector("default", replacement);

        assertSame(replacement, registry.getDefaultCollector(),
                "getDefaultCollector() must follow a replacement of the 'default' entry");
        registry.incrementCounter("c");
        assertEquals(1L, replacement.getCounterValue("c"),
                "new metrics must flow to the new default collector");
        assertEquals(0L, ((InMemoryMetricCollector) original).getCounterValue("c"),
                "the replaced collector must no longer receive default traffic");
    }

    @Test
    void metricTimerStopThenCloseRecordsOnce() {
        InMemoryMetricCollector collector = org.mockito.Mockito.spy(new InMemoryMetricCollector());
        MetricTimer timer = MetricTimer.start("t", collector);
        timer.stop();
        timer.close(); // close() used to record the same interval a second time

        org.mockito.Mockito.verify(collector, org.mockito.Mockito.times(1))
                .recordTimer(org.mockito.Mockito.eq("t"), org.mockito.Mockito.anyLong());
    }
}
