package io.github.cuihairu.redis.streaming.registry.heartbeat;

import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThreshold;
import io.github.cuihairu.redis.streaming.registry.metrics.ChangeThresholdType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HeartbeatStateManagerTest {

    @Test
    public void testFirstMetricsCollectionTriggersMetricsUpdate() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ZERO);
        cfg.setHeartbeatInterval(Duration.ofSeconds(3600));

        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);
        UpdateDecision d = mgr.shouldUpdateMetrics("svc", "i1", Map.of("k", 1));
        assertEquals(UpdateDecision.METRICS_UPDATE, d);
    }

    @Test
    public void testNoUpdateWhenWithinMetricsAndHeartbeatIntervals() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ofHours(1));
        cfg.setHeartbeatInterval(Duration.ofHours(1));

        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        Map<String, Object> metrics = Map.of("k", 1);
        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("svc", "i1", metrics));
        mgr.markMetricsUpdateCompleted("svc", "i1", metrics);

        assertEquals(UpdateDecision.NO_UPDATE, mgr.shouldUpdateMetrics("svc", "i1", metrics));
    }

    @Test
    public void testHeartbeatOnlyWhenHeartbeatDueButMetricsIntervalNotReached() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ofHours(1));
        cfg.setHeartbeatInterval(Duration.ZERO);

        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        Map<String, Object> metrics = Map.of("k", 1);
        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("svc", "i1", metrics));
        mgr.markMetricsUpdateCompleted("svc", "i1", metrics);

        assertEquals(UpdateDecision.HEARTBEAT_ONLY, mgr.shouldUpdateMetrics("svc", "i1", metrics));
    }

    @Test
    public void testForceMetricsUpdateAfterConsecutiveHeartbeats() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ZERO);
        cfg.setHeartbeatInterval(Duration.ofHours(1));
        cfg.setForceMetricsUpdateThreshold(2);
        cfg.setChangeThresholds(Map.of());

        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        Map<String, Object> metrics = Map.of("k", 1);
        mgr.shouldUpdateMetrics("svc", "i1", metrics);
        mgr.markMetricsUpdateCompleted("svc", "i1", metrics);

        mgr.markHeartbeatOnlyCompleted("svc", "i1");
        mgr.markHeartbeatOnlyCompleted("svc", "i1");

        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("svc", "i1", metrics));
    }

    @Test
    public void testThresholdBasedSignificantChangeWithNestedPath() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ZERO);
        cfg.setHeartbeatInterval(Duration.ofHours(1));
        cfg.setChangeThresholds(Map.of(
                "a.b", new ChangeThreshold(0, ChangeThresholdType.ANY)
        ));

        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        Map<String, Object> oldMetrics = new HashMap<>();
        oldMetrics.put("a", Map.of("b", 1));
        mgr.shouldUpdateMetrics("svc", "i1", oldMetrics);
        mgr.markMetricsUpdateCompleted("svc", "i1", oldMetrics);

        Map<String, Object> newMetrics = new HashMap<>();
        newMetrics.put("a", Map.of("b", 2));
        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdateMetrics("svc", "i1", newMetrics));
    }

    @Test
    public void testShouldUpdateMetadataDisabled() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setEnableMetadataChangeDetection(false);
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        assertEquals(UpdateDecision.NO_UPDATE, mgr.shouldUpdateMetadata("svc", "i1", Map.of("x", "y")));
    }

    @Test
    public void testShouldUpdateMetadataWhenEnabledAndChanged() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setEnableMetadataChangeDetection(true);
        cfg.setMetadataUpdateIntervalSeconds(0);

        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        Map<String, Object> meta = Map.of("region", "us");
        assertEquals(UpdateDecision.METADATA_UPDATE, mgr.shouldUpdateMetadata("svc", "i1", meta));
        mgr.markMetadataUpdateCompleted("svc", "i1", meta);

        assertEquals(UpdateDecision.NO_UPDATE, mgr.shouldUpdateMetadata("svc", "i1", meta));
        assertEquals(UpdateDecision.METADATA_UPDATE, mgr.shouldUpdateMetadata("svc", "i1", Map.of("region", "eu")));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testDeprecatedShouldUpdateDelegatesToMetrics() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ZERO);
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        assertEquals(UpdateDecision.METRICS_UPDATE, mgr.shouldUpdate("svc", "i1", Map.of("k", 1), true));
    }

    @Test
    public void testGetInstanceStateInfoAndRemove() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setMetricsInterval(Duration.ZERO);
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        assertNull(mgr.getInstanceStateInfo("svc", "i1"));

        mgr.shouldUpdateMetrics("svc", "i1", Map.of("k", 1));
        mgr.markMetricsUpdateCompleted("svc", "i1", Map.of("k", 1));

        Map<String, Object> info = mgr.getInstanceStateInfo("svc", "i1");
        assertNotNull(info);
        assertTrue(info.containsKey("lastHeartbeatTime"));
        assertTrue(info.containsKey("lastMetricsUpdateTime"));

        mgr.removeInstanceState("svc", "i1");
        assertNull(mgr.getInstanceStateInfo("svc", "i1"));
    }
}

