package io.github.cuihairu.redis.streaming.examples.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * Example demonstrating Checkpoint module usage.
 *
 * Requires Redis at redis://127.0.0.1:6379 (override via REDIS_URL).
 */
@Slf4j
public class CheckpointExample {

    public static void main(String[] args) throws Exception {
        log.info("Starting Checkpoint Example");

        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redisson = Redisson.create(config);

        try {
            RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "example:checkpoint:");
            RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 3);

            demonstrateBasicCheckpoint(coordinator);
            demonstrateStateSnapshot(coordinator);
            demonstrateCheckpointRecovery(coordinator);
            demonstrateCleanup(coordinator);

            coordinator.close();
            log.info("Checkpoint Example completed successfully");
        } finally {
            redisson.shutdown();
        }
    }

    private static void demonstrateBasicCheckpoint(RedisCheckpointCoordinator coordinator) {
        log.info("=== Basic Checkpoint Example ===");

        long checkpointId = coordinator.triggerCheckpoint();
        log.info("Triggered checkpoint: {}", checkpointId);

        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");
        coordinator.acknowledgeCheckpoint(checkpointId, "task-2");
        coordinator.acknowledgeCheckpoint(checkpointId, "task-3");

        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);
        log.info("Checkpoint {} completed: {}", checkpointId, checkpoint.isCompleted());
    }

    private static void demonstrateStateSnapshot(RedisCheckpointCoordinator coordinator) {
        log.info("=== State Snapshot Example ===");

        long checkpointId = coordinator.triggerCheckpoint();
        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);

        Checkpoint.StateSnapshot snapshot = checkpoint.getStateSnapshot();
        snapshot.putState("user-count", 1000);
        snapshot.putState("last-processed-id", 12345L);
        snapshot.putState("processing-state", "RUNNING");

        log.info("Added state to checkpoint {}: keys={}", checkpointId, snapshot.getKeys());
        checkpoint.markCompleted();
    }

    private static void demonstrateCheckpointRecovery(RedisCheckpointCoordinator coordinator) {
        log.info("=== Checkpoint Recovery Example ===");

        Checkpoint latest = coordinator.getLatestCheckpoint();
        if (latest == null) {
            log.info("No checkpoints available for recovery");
            return;
        }

        coordinator.restoreFromCheckpoint(latest.getCheckpointId());
        log.info("Restored from checkpoint: {}", latest.getCheckpointId());
    }

    private static void demonstrateCleanup(RedisCheckpointCoordinator coordinator) {
        log.info("=== Cleanup Example ===");

        for (int i = 0; i < 5; i++) {
            coordinator.triggerCheckpoint();
        }
        int deleted = coordinator.cleanupOldCheckpoints(2);
        log.info("Cleaned up {} old checkpoints, keeping 2 most recent", deleted);
    }
}

