package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * Example demonstrating Checkpoint module usage
 */
@Slf4j
public class CheckpointExample {

    public static void main(String[] args) throws Exception {
        log.info("Starting Checkpoint Example");

        // Setup Redis connection
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        RedissonClient redisson = Redisson.create(config);

        try {
            // Create checkpoint storage and coordinator
            RedisCheckpointStorage storage = new RedisCheckpointStorage(redisson, "example:checkpoint:");
            RedisCheckpointCoordinator coordinator = new RedisCheckpointCoordinator(storage, 3);

            // Example 1: Basic checkpoint workflow
            demonstrateBasicCheckpoint(coordinator);

            // Example 2: State snapshot
            demonstrateStateSnapshot(coordinator);

            // Example 3: Checkpoint recovery
            demonstrateCheckpointRecovery(coordinator);

            // Example 4: Cleanup old checkpoints
            demonstrateCleanup(coordinator);

            coordinator.close();
            log.info("Checkpoint Example completed successfully");

        } finally {
            redisson.shutdown();
        }
    }

    private static void demonstrateBasicCheckpoint(RedisCheckpointCoordinator coordinator) {
        log.info("=== Basic Checkpoint Example ===");

        // Trigger a checkpoint
        long checkpointId = coordinator.triggerCheckpoint();
        log.info("Triggered checkpoint: {}", checkpointId);

        // Simulate tasks acknowledging the checkpoint
        coordinator.acknowledgeCheckpoint(checkpointId, "task-1");
        log.info("Task 1 acknowledged checkpoint {}", checkpointId);

        coordinator.acknowledgeCheckpoint(checkpointId, "task-2");
        log.info("Task 2 acknowledged checkpoint {}", checkpointId);

        coordinator.acknowledgeCheckpoint(checkpointId, "task-3");
        log.info("Task 3 acknowledged checkpoint {}", checkpointId);

        // Check if completed
        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);
        log.info("Checkpoint {} completed: {}", checkpointId, checkpoint.isCompleted());
    }

    private static void demonstrateStateSnapshot(RedisCheckpointCoordinator coordinator) {
        log.info("=== State Snapshot Example ===");

        // Trigger checkpoint
        long checkpointId = coordinator.triggerCheckpoint();
        Checkpoint checkpoint = coordinator.getCheckpoint(checkpointId);

        // Add application state to snapshot
        Checkpoint.StateSnapshot snapshot = checkpoint.getStateSnapshot();
        snapshot.putState("user-count", 1000);
        snapshot.putState("last-processed-id", 12345L);
        snapshot.putState("processing-state", "RUNNING");

        log.info("Added state to checkpoint {}:", checkpointId);
        for (String key : snapshot.getKeys()) {
            log.info("  {} = {}", key, snapshot.getState(key));
        }

        // Complete checkpoint (would normally happen after all tasks ack)
        checkpoint.markCompleted();
    }

    private static void demonstrateCheckpointRecovery(RedisCheckpointCoordinator coordinator) {
        log.info("=== Checkpoint Recovery Example ===");

        // Get latest checkpoint
        Checkpoint latest = coordinator.getLatestCheckpoint();

        if (latest != null) {
            log.info("Latest checkpoint ID: {}", latest.getCheckpointId());
            log.info("Checkpoint timestamp: {}", latest.getTimestamp());
            log.info("Checkpoint completed: {}", latest.isCompleted());

            // Restore from this checkpoint
            coordinator.restoreFromCheckpoint(latest.getCheckpointId());

            // Access restored state
            Checkpoint.StateSnapshot snapshot = latest.getStateSnapshot();
            for (String key : snapshot.getKeys()) {
                Object value = snapshot.getState(key);
                log.info("Restored state: {} = {}", key, value);
            }
        } else {
            log.info("No checkpoints available for recovery");
        }
    }

    private static void demonstrateCleanup(RedisCheckpointCoordinator coordinator) {
        log.info("=== Cleanup Example ===");

        // Create some checkpoints
        log.info("Creating 5 checkpoints...");
        for (int i = 0; i < 5; i++) {
            long checkpointId = coordinator.triggerCheckpoint();
            log.info("Created checkpoint {}", checkpointId);
        }

        log.info("Pending checkpoints: {}", coordinator.getPendingCheckpointCount());

        // Cleanup old checkpoints, keep only 2 most recent
        int deleted = coordinator.cleanupOldCheckpoints(2);
        log.info("Cleaned up {} old checkpoints, keeping 2 most recent", deleted);

        // Verify
        Checkpoint latest = coordinator.getLatestCheckpoint();
        if (latest != null) {
            log.info("Latest checkpoint after cleanup: {}", latest.getCheckpointId());
        }
    }
}
