package io.github.cuihairu.redis.streaming.registry.heartbeat;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for B-33: at registration the provider records the instance's
 * metadata hash via markMetadataUpdateCompleted, but that method used
 * {@code instanceStates.get} — a silent no-op when no state entry existed yet
 * (nothing had ever consulted a decision for the instance). With metadata change
 * detection enabled, the first heartbeat then compared the real hash against the
 * fresh state's default 0 and wrongly decided METADATA_UPDATE, triggering a
 * pointless re-discovery for every subscriber.
 */
class HeartbeatStateManagerMetadataRegistrationTest {

    private HeartbeatStateManager newManager() {
        HeartbeatConfig config = new HeartbeatConfig();
        config.setEnableMetadataChangeDetection(true);
        return new HeartbeatStateManager(config);
    }

    @Test
    void markMetadataUpdateCompletedOnFreshManagerRecordsHash() {
        HeartbeatStateManager manager = newManager();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", "1");

        // registration-time marking on a state manager that has seen nothing yet
        manager.markMetadataUpdateCompleted("svc", "i1", metadata);

        // first heartbeat with unchanged metadata must not be flagged as a metadata change
        assertEquals(UpdateDecision.NO_UPDATE,
                manager.shouldUpdateMetadata("svc", "i1", new HashMap<>(metadata)),
                "unchanged metadata must not trigger METADATA_UPDATE (old code: hash never recorded, 0 != h -> METADATA_UPDATE)");
    }

    @Test
    void metadataChangeAfterRegistrationIsStillDetected() {
        HeartbeatConfig config = new HeartbeatConfig();
        config.setEnableMetadataChangeDetection(true);
        config.setMetadataUpdateIntervalSeconds(0); // disable rate limiting to isolate hash comparison
        HeartbeatStateManager manager = new HeartbeatStateManager(config);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("version", "1");
        manager.markMetadataUpdateCompleted("svc", "i1", metadata);

        Map<String, Object> changed = new HashMap<>(metadata);
        changed.put("version", "2");
        assertEquals(UpdateDecision.METADATA_UPDATE,
                manager.shouldUpdateMetadata("svc", "i1", changed),
                "real metadata changes must still be detected after registration-time seeding");
    }
}
