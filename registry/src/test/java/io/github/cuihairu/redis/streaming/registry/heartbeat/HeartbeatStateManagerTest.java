package io.github.cuihairu.redis.streaming.registry.heartbeat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HeartbeatStateManagerTest {

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
    public void testMetadataUpdateCountsAsHeartbeat() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setEnableMetadataChangeDetection(true);
        cfg.setMetadataUpdateIntervalSeconds(0);
        cfg.setHeartbeatInterval(Duration.ofSeconds(60));
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        Map<String, Object> meta = Map.of("region", "us");
        assertEquals(UpdateDecision.METADATA_UPDATE, mgr.shouldUpdateMetadata("svc", "i1", meta));
        mgr.markMetadataUpdateCompleted("svc", "i1", meta);

        // a metadata update also advances the heartbeat timestamp, so no bare touch is due
        assertEquals(UpdateDecision.NO_UPDATE, mgr.shouldHeartbeatOnly("svc", "i1"));
    }

    @Test
    public void testShouldHeartbeatOnlyDecidesByHeartbeatInterval() {
        // A due heartbeat must surface as HEARTBEAT_ONLY so the TTL/score refresh is never skipped.
        HeartbeatConfig cfg = new HeartbeatConfig();
        cfg.setHeartbeatInterval(Duration.ofSeconds(60));
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        // first sight: state created with lastHeartbeatTime=0 -> heartbeat due
        assertEquals(UpdateDecision.HEARTBEAT_ONLY, mgr.shouldHeartbeatOnly("svc-hb", "i1"));

        // after the heartbeat is marked completed, within the interval -> no update
        mgr.markHeartbeatOnlyCompleted("svc-hb", "i1");
        assertEquals(UpdateDecision.NO_UPDATE, mgr.shouldHeartbeatOnly("svc-hb", "i1"));
    }

    @Test
    public void testGetInstanceStateInfoAndRemove() {
        HeartbeatConfig cfg = new HeartbeatConfig();
        HeartbeatStateManager mgr = new HeartbeatStateManager(cfg);

        assertNull(mgr.getInstanceStateInfo("svc", "i1"));

        mgr.shouldHeartbeatOnly("svc", "i1"); // decision seeds the state entry
        mgr.markHeartbeatOnlyCompleted("svc", "i1");

        Map<String, Object> info = mgr.getInstanceStateInfo("svc", "i1");
        assertNotNull(info);
        assertTrue(info.containsKey("lastHeartbeatTime"));

        mgr.removeInstanceState("svc", "i1");
        assertNull(mgr.getInstanceStateInfo("svc", "i1"));
    }
}
