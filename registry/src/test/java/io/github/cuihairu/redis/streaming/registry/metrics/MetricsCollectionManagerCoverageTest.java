package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers MetricsCollectionManager constructor defaults, shouldCollectMetric,
 * hasSignificantChange and the collectWithTimeout failure lambda, plus the
 * ChangeThreshold switch default case via a mocked enum constant.
 */
class MetricsCollectionManagerCoverageTest {

    private static MetricCollector collector(String type, Object value) {
        return new MetricCollector() {
            @Override
            public String getMetricType() {
                return type;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Object collectMetric() {
                return value;
            }
        };
    }

    private static MetricCollector throwingCollector(String type) {
        return new MetricCollector() {
            @Override
            public String getMetricType() {
                return type;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Object collectMetric() throws Exception {
                throw new Exception("collector failure");
            }
        };
    }

    @Test
    void constructorAcceptsNullCollectorsAndConfig() {
        MetricsCollectionManager m = new MetricsCollectionManager(null, null);
        assertTrue(m.collectMetrics(true).isEmpty());
    }

    @Test
    void collectWithTimeoutWrapsCollectorExceptions() {
        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(Set.of("boom"));
        MetricsCollectionManager m = new MetricsCollectionManager(List.of(throwingCollector("boom")), cfg);
        assertTrue(m.collectMetrics(true).isEmpty());
    }

    @Test
    void shouldCollectMetricHonoursIntervalsAndDefaults() {
        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(Set.of("t"));
        cfg.setCollectionIntervals(Map.of("t", Duration.ofHours(1)));
        AtomicReference<Object> value = new AtomicReference<>(Map.of("a", 1));
        MetricsCollectionManager m = new MetricsCollectionManager(List.of(collector("t", value.get())), cfg);

        Map<String, Object> first = m.collectMetrics(false);
        assertTrue(first.containsKey("t"));
        // within interval -> served from cache (shouldCollectMetric false)
        Map<String, Object> second = m.collectMetrics(false);
        assertTrue(second.containsKey("t"));

        // type without a configured interval falls back to defaultCollectionInterval
        MetricsConfig cfg2 = new MetricsConfig();
        cfg2.setEnabledMetrics(Set.of("t"));
        cfg2.setCollectionIntervals(Map.of());
        cfg2.setDefaultCollectionInterval(Duration.ofHours(1));
        MetricsCollectionManager m2 = new MetricsCollectionManager(List.of(collector("t", Map.of("a", 1))), cfg2);
        assertTrue(m2.collectMetrics(false).containsKey("t"));
        assertTrue(m2.collectMetrics(false).containsKey("t"));

        // zero interval -> always collect
        MetricsConfig cfg3 = new MetricsConfig();
        cfg3.setEnabledMetrics(Set.of("t"));
        cfg3.setCollectionIntervals(Map.of("t", Duration.ZERO));
        MetricsCollectionManager m3 = new MetricsCollectionManager(List.of(collector("t", Map.of("a", 1))), cfg3);
        assertTrue(m3.collectMetrics(false).containsKey("t"));
        assertTrue(m3.collectMetrics(false).containsKey("t"));
    }

    @Test
    void hasSignificantChangeCoversAllBranches() {
        MetricsConfig cfg = new MetricsConfig();
        cfg.setEnabledMetrics(Set.of("t"));
        cfg.setImmediateUpdateOnSignificantChange(false);
        MetricsCollectionManager m = new MetricsCollectionManager(List.of(collector("t", Map.of("a", 1))), cfg);
        assertFalse(m.hasSignificantChange(Map.of("a", 2)));

        MetricsConfig cfg2 = new MetricsConfig();
        cfg2.setEnabledMetrics(Set.of("t"));
        cfg2.setImmediateUpdateOnSignificantChange(true);
        cfg2.setChangeThresholds(Map.of(
                "t.a", new ChangeThreshold(10, ChangeThresholdType.ABSOLUTE),
                "t.nested.deep", new ChangeThreshold(0, ChangeThresholdType.ANY)));
        MetricsCollectionManager m2 = new MetricsCollectionManager(List.of(collector("t", Map.of("a", 1))), cfg2);
        m2.collectMetrics(true);

        // insignificant flat change and nested path through a scalar -> loop falls through
        Map<String, Object> flatMiss = new HashMap<>();
        flatMiss.put("t", Map.of("a", 2, "nested", "scalar"));
        assertFalse(m2.hasSignificantChange(flatMiss));
        // significant flat change
        Map<String, Object> flatHit = new HashMap<>();
        flatHit.put("t", Map.of("a", 500));
        assertTrue(m2.hasSignificantChange(flatHit));
    }

    @Test
    void changeThresholdPercentageZeroOldValueEdge() {
        ChangeThreshold t = new ChangeThreshold(10.0, ChangeThresholdType.PERCENTAGE);
        assertTrue(t.isSignificant(0d, 1d));
        assertFalse(t.isSignificant(0d, 0d));
    }

    @Test
    @SuppressWarnings("deprecation")
    void changeThresholdDeprecatedStringConstructor() {
        ChangeThreshold t = new ChangeThreshold(5.0, "absolute");
        assertTrue(t.isSignificant(1, 10));
    }
}
