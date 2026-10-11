package io.github.cuihairu.redis.streaming.registry.heartbeat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers HeartbeatStateManager constructor default and the decision
 * branches of shouldHeartbeatOnly, shouldUpdateMetadata and the mark helpers.
 */
class HeartbeatStateManagerCoverageTest {

    @Test
    void constructorAcceptsNullConfig() {
        HeartbeatStateManager mgr = new HeartbeatStateManager(null);
        assertNotNull(mgr.shouldHeartbeatOnly("s", "i"));
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
        // heartbeat marking on an unknown instance is a no-op (no state entry yet)
        mgr.markHeartbeatOnlyCompleted("ghost", "g1");
        assertNull(mgr.getInstanceStateInfo("ghost", "g1"));
        // metadata marking seeds the entry: registration records the baseline hash before
        // any decision ran (B-33 — a no-op here made the first heartbeat report a bogus
        // METADATA_UPDATE)
        mgr.markMetadataUpdateCompleted("ghost", "g1", Map.of("k", 1));
        assertNotNull(mgr.getInstanceStateInfo("ghost", "g1"));
        mgr.removeInstanceState("ghost", "g1");
        assertNull(mgr.getInstanceStateInfo("ghost", "g1"));
        assertNull(mgr.getInstanceStateInfo("s", "i"));
        mgr.shouldHeartbeatOnly("s", "i");
        assertNotNull(mgr.getInstanceStateInfo("s", "i"));
        mgr.removeInstanceState("s", "i");
        assertNull(mgr.getInstanceStateInfo("s", "i"));
    }
}
