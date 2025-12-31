package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCollectionManagerTest {

    @Test
    void collectMetricsUsesCacheAndStillExportsDottedAndLegacyKeys() {
        AtomicInteger calls = new AtomicInteger(0);
        MetricCollector cpuCollector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "cpu";
            }

            @Override
            public Object collectMetric() {
                calls.incrementAndGet();
                return Map.of("processCpuLoad", 0.42);
            }
        };

        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(Set.of("cpu"));
        cfg.setCollectionIntervals(Map.of("cpu", Duration.ofHours(1)));
        cfg.setDefaultCollectionInterval(Duration.ofHours(1));

        MetricsCollectionManager mgr = new MetricsCollectionManager(List.of(cpuCollector), cfg);

        Map<String, Object> first = mgr.collectMetrics(false);
        assertEquals(1, calls.get());
        assertEquals(0.42, ((Map<?, ?>) first.get("cpu")).get("processCpuLoad"));
        assertEquals(0.42, first.get(MetricKeys.CPU_PROCESS_LOAD));
        assertEquals(0.42, first.get(MetricKeys.LEGACY_CPU_PROCESS_LOAD));

        Map<String, Object> second = mgr.collectMetrics(false);
        assertEquals(1, calls.get(), "Second collect should hit cache");
        assertEquals(0.42, ((Map<?, ?>) second.get("cpu")).get("processCpuLoad"));
        assertEquals(0.42, second.get(MetricKeys.CPU_PROCESS_LOAD));
        assertEquals(0.42, second.get(MetricKeys.LEGACY_CPU_PROCESS_LOAD));
    }

    @Test
    void collectMetricsSkipsCollectorWhenTimeoutOccurs() {
        MetricCollector slowCollector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "cpu";
            }

            @Override
            public Object collectMetric() throws Exception {
                Thread.sleep(100);
                return Map.of("processCpuLoad", 0.1);
            }
        };

        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(Set.of("cpu"));
        cfg.setCollectionTimeout(Duration.ofMillis(5));
        cfg.setDefaultCollectionInterval(Duration.ofMillis(0));
        cfg.setCollectionIntervals(Map.of("cpu", Duration.ofMillis(0)));

        MetricsCollectionManager mgr = new MetricsCollectionManager(List.of(slowCollector), cfg);
        Map<String, Object> out = mgr.collectMetrics(true);
        assertTrue(out.isEmpty());
    }

    @Test
    void hasSignificantChangeDetectsConfiguredThreshold() {
        MetricCollector cpuCollector = new MetricCollector() {
            @Override
            public String getMetricType() {
                return "cpu";
            }

            @Override
            public Object collectMetric() {
                return Map.of("processCpuLoad", 0.10);
            }
        };

        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(Set.of("cpu"));
        cfg.setImmediateUpdateOnSignificantChange(true);
        cfg.setChangeThresholds(Map.of(
                MetricKeys.CPU_PROCESS_LOAD,
                new ChangeThreshold(0.05, ChangeThresholdType.ABSOLUTE)
        ));

        MetricsCollectionManager mgr = new MetricsCollectionManager(List.of(cpuCollector), cfg);
        mgr.collectMetrics(true); // establish lastMetrics

        Map<String, Object> newCpu = new HashMap<>();
        newCpu.put("processCpuLoad", 0.30);
        Map<String, Object> newMetrics = new HashMap<>();
        newMetrics.put("cpu", newCpu);
        newMetrics.put(MetricKeys.CPU_PROCESS_LOAD, 0.30);

        assertTrue(mgr.hasSignificantChange(newMetrics));

        cfg.setImmediateUpdateOnSignificantChange(false);
        assertFalse(mgr.hasSignificantChange(newMetrics));
    }
}

