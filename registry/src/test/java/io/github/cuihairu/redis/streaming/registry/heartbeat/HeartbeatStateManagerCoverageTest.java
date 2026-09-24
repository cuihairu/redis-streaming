package io.github.cuihairu.redis.streaming.registry.heartbeat;

import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThreshold;
import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThresholdType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers HeartbeatStateManager constructor default and the decision/threshold
 * branches of shouldUpdateMetrics, shouldUpdateMetadata, hasSignificantMetricsChange
 * and getNestedValue.
 */
class HeartbeatStateManagerCoverageTest {

    @Test
    void constructorAcceptsNullConfig() {
        HeartbeatStateManager mgr = new HeartbeatStateManager(null);
        assertNotNull(mgr.shouldUpdateMetrics("s", "i", Map.of("k", 1)));
    }

    @Test
    void shouldUpdateMetricsIntervalGateReturnsHeartbeatOnlyOrNoUpdate() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ofHours(1));
        cfg.setHeartbeatInterval(Duration.ZERO);
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        Map<String, Object> metrics = Map.of("k", 1);
        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("s", "i", metrics));
        mgr.markMetricsUpdateCompleted("s", "i", metrics);
        // within metrics interval but heartbeat due
        assertEquals(UpdateDecision.HEARTBEAT_ONLY, mgr.shouldUpdateMetrics("s", "i", metrics));

        HeartbeatConfig cfg2 = new HeartbeatConfig();
        cfg2.setMetricsInterval(Duration.ofHours(1));
        cfg2.setHeartbeatInterval(Duration.ofHours(1));
        HeartbeatStateManager mgr2 = new HeartbeatStateManager(cfg2);
        mgr2.shouldUpdateMetrics("s", "i", metrics);
        mgr2.markMetricsUpdateCompleted("s", "i", metrics);
        // within metrics interval and heartbeat not due
        assertEquals(UpdateDecision.NO_UPDATE, mgr2.shouldUpdateMetrics("s", "i", metrics));
    }

    @Test
    void shouldUpdateMetricsWithEmptyThresholdsDetectsHashChangeOnly() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ZERO);
        cfg.setHeartbeatInterval(Duration.ofHours(1));
        cfg.setChangeThresholds(Map.of());
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        Map<String, Object> m1 = Map.of("k", 1);
        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("s", "i", m1));
        mgr.markMetricsUpdateCompleted("s", "i", m1);

        // identical hash -> not significant -> heartbeat not due -> NO_UPDATE
        assertEquals(UpdateDecision.NO_UPDATE, mgr.shouldUpdateMetrics("s", "i", m1));
        // different hash with empty thresholds -> significant
        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("s", "i", Map.of("k", 2)));
    }

    @Test
    void shouldUpdateMetricsWithNullThresholdsDetectsHashChange() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ZERO);
        cfg.setHeartbeatInterval(Duration.ofHours(1));
        cfg.setChangeThresholds(null);
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        Map<String, Object> m1 = Map.of("k", 1);
        mgr.shouldUpdateMetrics("s", "i", m1);
        mgr.markMetricsUpdateCompleted("s", "i", m1);
        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("s", "i", Map.of("k", 2)));
    }

    @Test
    void hasSignificantMetricsChangeCoversThresholdLoopBranches() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ZERO);
        cfg.setHeartbeatInterval(Duration.ofHours(1));
        cfg.setChangeThresholds(Map.of(
                "a.b", new ChangeThreshold(0, ChangeThresholdType.ANY),
                "flat", new ChangeThreshold(1000, ChangeThresholdType.ABSOLUTE)));
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        // nested path into a scalar: getNestedValue returns null for both -> continue;
        // flat change below threshold -> not significant -> loop falls through to false
        Map<String, Object> m1 = new HashMap<>();
        m1.put("a", "scalar");
        m1.put("flat", 1);
        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("s", "i", m1));
        mgr.markMetricsUpdateCompleted("s", "i", m1);

        Map<String, Object> m2 = new HashMap<>();
        m2.put("a", "other");
        m2.put("flat", 2);
        assertEquals(UpdateDecision.NO_UPDATE, mgr.shouldUpdateMetrics("s", "i", m2));

        // significant nested change triggers update
        Map<String, Object> m3 = new HashMap<>();
        m3.put("a", Map.of("b", 42));
        m3.put("flat", 2);
        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("s", "i", m3));
    }

    @Test
    void shouldUpdateMetadataCoversDisabledIntervalChangedAndUnchanged() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setEnableMetadataChangeDetection(false);
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);
        assertEquals(UpdateDecision.NO_UPDATE, mgr.shouldUpdateMetadata("s", "i", Map.of("r", "1")));

        HeartbeatConfig cfg2 = new HeartbeatConfig();
        cfg2.setEnableMetadataChangeDetection(true);
        cfg2.setMetadataUpdateIntervalSeconds(3600);
        HeartbeatStateManager mgr2 = new HeartbeatStateManager(cfg2);
        Map<String, Object> md = Map.of("r", "1");
        mgr2.shouldUpdateMetadata("s", "i", md);
        mgr2.markMetadataUpdateCompleted("s", "i", md);
        // within min interval -> NO_UPDATE even though content differs
        assertEquals(UpdateDecision.NO_UPDATE, mgr2.shouldUpdateMetadata("s", "i", Map.of("r", "2")));

        HeartbeatConfig cfg3 = new HeartbeatConfig();
        cfg3.setEnableMetadataChangeDetection(true);
        cfg3.setMetadataUpdateIntervalSeconds(0);
        HeartbeatStateManager mgr3 = new HeartbeatStateManager(cfg3);
        mgr3.shouldUpdateMetadata("s", "i", md);
        mgr3.markMetadataUpdateCompleted("s", "i", md);
        // unchanged hash -> NO_UPDATE
        assertEquals(UpdateDecision.NO_UPDATE, mgr3.shouldUpdateMetadata("s", "i", md));
        // changed hash -> METADATA_UPDATE
        assertEquals(UpdateDecision.METADATA_UPDATE, mgr3.shouldUpdateMetadata("s", "i", Map.of("r", "2")));
    }

    @Test
    void stateInfoAndRemovalHelpers() {
        HeartbeatStateManager mgr = new HeartbeatStateManager(new HeartbeatConfig());
        // marking an unknown instance is a no-op (no state entry yet)
        mgr.markMetricsUpdateCompleted("ghost", "g1", Map.of("k", 1));
        mgr.markMetadataUpdateCompleted("ghost", "g1", Map.of("k", 1));
        mgr.markHeartbeatOnlyCompleted("ghost", "g1");
        assertNull(mgr.getInstanceStateInfo("ghost", "g1"));
        assertNull(mgr.getInstanceStateInfo("s", "i"));
        mgr.shouldUpdateMetrics("s", "i", Map.of("k", 1));
        assertNotNull(mgr.getInstanceStateInfo("s", "i"));
        mgr.removeInstanceState("s", "i");
        assertNull(mgr.getInstanceStateInfo("s", "i"));
    }
}
