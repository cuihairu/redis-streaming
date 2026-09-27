package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-Redis integration for the checkpoint module, reachable in the default {@code test}
 * task: every test probes the configured Redis first and skips cleanly when it is absent
 * (no hard failure on environments without one), and the client is shut down afterwards —
 * the same reachability-gating convention the starter module's real-client test uses.
 *
 * <p>Covers the distributed coordination path end to end: task acknowledgements complete a
 * checkpoint whose operator state was written into the snapshot, a fresh storage instance
 * (the restart view) recovers the state through the restore sink, and an incomplete
 * checkpoint is never served as a recovery point (B-14).
 */
class CheckpointRedisLiveIntegrationTest {

    private static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");

    private static RedissonClient createGatedClient() {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping live checkpoint tests");
        Config config = new Config();
        config.useSingleServer().setAddress(REDIS_URL);
        return Redisson.create(config);
    }

    private static boolean reachable() {
        Matcher m = Pattern.compile("://([^/:]+):(\\d+)").matcher(REDIS_URL);
        String host = "127.0.0.1";
        int port = 6379;
        if (m.find()) {
            host = m.group(1);
            port = Integer.parseInt(m.group(2));
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void taskAcknowledgementsCompleteTheCheckpointAndStateSurvivesRestartRecovery() throws Exception {
        RedissonClient redisson = createGatedClient();
        try {
            String prefix = "test:ckpt-live:" + UUID.randomUUID().toString().substring(0, 8) + ":";
            RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, prefix);
            try {
                RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 2, 30_000);
                try {
                    long checkpointId = coordinator.triggerCheckpoint();
                    assertEquals(1, coordinator.getPendingCheckpointCount());

                    // operators write their state into the pending checkpoint's snapshot
                    Checkpoint pending = storage.loadCheckpoint(checkpointId);
                    assertNotNull(pending);
                    pending.getStateSnapshot().putState("counter", 42);
                    pending.getStateSnapshot().putState("offsets", Map.of("p0", 11L, "p1", 22L));
                    storage.storeCheckpoint(pending);

                    // both tasks acknowledge → the checkpoint completes
                    coordinator.acknowledgeCheckpoint(checkpointId, "task-1");
                    coordinator.acknowledgeCheckpoint(checkpointId, "task-2");
                    assertEquals(0, coordinator.getPendingCheckpointCount());
                    assertTrue(storage.loadCheckpoint(checkpointId).isCompleted());

                    // restart view: a brand-new storage/coordinator pair over the same Redis
                    RedisCheckpointStorage restarted = new RedisCheckpointStorage(redisson, prefix);
                    Checkpoint latest = restarted.getLatestCheckpoint();
                    assertNotNull(latest, "the completed checkpoint must be the recovery point");
                    assertEquals(checkpointId, latest.getCheckpointId());

                    Map<String, Object> restored = new HashMap<>();
                    int count = new RedisCheckpointCoordinator(restarted, 1, 30_000)
                            .restoreFromCheckpoint(checkpointId, restored::put);

                    assertEquals(2, count);
                    assertEquals(42, restored.get("counter"));
                    assertEquals(Map.of("p0", 11L, "p1", 22L), restored.get("offsets"));
                } finally {
                    coordinator.close();
                }
            } finally {
                storage.close();
                redisson.getKeys().deleteByPattern(prefix + "*");
            }
        } finally {
            redisson.shutdown();
        }
    }

    @Test
    void incompleteCheckpointIsNeverServedAsARecoveryPoint() throws Exception {
        RedissonClient redisson = createGatedClient();
        try {
            String prefix = "test:ckpt-live:" + UUID.randomUUID().toString().substring(0, 8) + ":";
            RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, prefix);
            try {
                RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 2, 30_000);
                try {
                    long checkpointId = coordinator.triggerCheckpoint();

                    Checkpoint pending = storage.loadCheckpoint(checkpointId);
                    pending.getStateSnapshot().putState("torn", "state");
                    storage.storeCheckpoint(pending);

                    // only 1 of 2 acks — the checkpoint stays incomplete
                    coordinator.acknowledgeCheckpoint(checkpointId, "task-1");

                    // B-14: not a recovery point for getLatestCheckpoint, and restore refuses it
                    assertNull(storage.getLatestCheckpoint(),
                            "an incomplete checkpoint must not be served as the latest recovery point");
                    List<String> transferred = new java.util.ArrayList<>();
                    assertEquals(-1, new RedisCheckpointCoordinator(storage, 1, 30_000)
                            .restoreFromCheckpoint(checkpointId, (key, value) -> transferred.add(key)));
                    assertTrue(transferred.isEmpty(), "no state may be handed to the sink for a torn checkpoint");
                } finally {
                    coordinator.close();
                }
            } finally {
                storage.close();
                redisson.getKeys().deleteByPattern(prefix + "*");
            }
        } finally {
            redisson.shutdown();
        }
    }
}
