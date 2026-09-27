package io.github.cuihairu.redis.streaming.examples.checkpoint;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link CheckpointExample}'s demo sections directly (the demo methods were made
 * package-visible for this — example-side change only). The example hard-requires Redis
 * (its {@code main} creates the client eagerly), so everything here is reachability-gated:
 * it runs against the configured Redis when one is present and skips cleanly otherwise.
 */
class CheckpointExampleTest {

    private static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");

    @Test
    void mainRunsEndToEndAgainstRedis() {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping checkpoint example");
        assertDoesNotThrow(() -> CheckpointExample.main(new String[0]));
    }

    @Test
    void demoSectionsProduceTheirDocumentedEffects() throws Exception {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping checkpoint example");

        RedissonClient redisson = redisson();
        try {
            String prefix = "example:checkpoint:test:" + UUID_HEX + ":";
            RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, prefix);
            try {
                RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 3);
                try {
                    // basic: 3 acks complete the checkpoint
                    CheckpointExample.demonstrateBasicCheckpoint(coordinator);
                    long firstId = latestIdIn(storage);
                    Checkpoint completed = storage.loadCheckpoint(firstId);
                    assertNotNull(completed);
                    assertTrue(completed.isCompleted(),
                            "demonstrateBasicCheckpoint acknowledges 3 tasks — the checkpoint must complete");

                    // snapshot: state lands in a NEW pending checkpoint (not persisted back
                    // by markCompleted — the example only demonstrates the in-memory API)
                    CheckpointExample.demonstrateStateSnapshot(coordinator);
                    assertDoesNotThrow(() -> CheckpointExample.demonstrateCheckpointRecovery(coordinator));

                    // cleanup: trims the storage down to the 2 most recent checkpoints
                    CheckpointExample.demonstrateCleanup(coordinator);
                    assertTrue(storage.listCheckpoints(100).size() <= 2,
                            "demonstrateCleanup keeps only the 2 most recent checkpoints");
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

    private static final String UUID_HEX = java.util.UUID.randomUUID().toString().substring(0, 8);

    private static long latestIdIn(RedisCheckpointStorage storage) throws Exception {
        Checkpoint latest = storage.getLatestCheckpoint();
        assertNotNull(latest, "the example must have persisted at least one completed checkpoint");
        return latest.getCheckpointId();
    }

    private static RedissonClient redisson() {
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
}
