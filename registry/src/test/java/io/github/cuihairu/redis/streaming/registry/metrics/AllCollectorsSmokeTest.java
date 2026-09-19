package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Smoke coverage for every built-in metric collector (real MXBeans). */
class AllCollectorsSmokeTest {

    private void probe(MetricCollector collector) {
        assertNotNull(collector.getMetricType());
        if (collector.isAvailable()) {
            try {
                assertNotNull(collector.collectMetric());
            } catch (Exception e) {
                fail("collectMetric must not throw for " + collector.getMetricType(), e);
            }
        }
    }

    @Test
    void allBuiltInCollectorsRespond() {
        probe(new CpuMetricCollector());
        probe(new MemoryMetricCollector());
        probe(new DiskMetricCollector());
        probe(new NetworkMetricCollector());
        probe(new GcMetricCollector());
        probe(new ApplicationMetricCollector());
    }

    @Test
    void configAndThresholdHelpers() {
        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(java.util.Set.of("cpu"));
        cfg.setDefaultCollectionInterval(java.time.Duration.ofSeconds(5));
        cfg.setCollectionTimeout(java.time.Duration.ofSeconds(1));
        cfg.setImmediateUpdateOnSignificantChange(true);
        assertEquals(java.time.Duration.ofSeconds(5), cfg.getDefaultCollectionInterval());

        ChangeThreshold t = new ChangeThreshold(10.0, ChangeThresholdType.PERCENTAGE);
        assertTrue(t.isSignificant(1.0, 50.0));
        assertFalse(t.isSignificant(1.0, 1.02));
        assertNotNull(new ChangeThreshold(5.0, ChangeThresholdType.PERCENTAGE.getValue()).toString());

        assertTrue(CollectionCost.values().length > 0);
        assertNotNull(CollectionCost.values()[0].name());

        MetricsGlobal.setDefaultConfig(cfg);
        assertNotNull(MetricsGlobal.getOrDefault());
        assertNotNull(ChangeThresholdType.fromValue(ChangeThresholdType.PERCENTAGE.getValue()));
        assertNotNull(ChangeThresholdType.PERCENTAGE.toString());
    }
}
