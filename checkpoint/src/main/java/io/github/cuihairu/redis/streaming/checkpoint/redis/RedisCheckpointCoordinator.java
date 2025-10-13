package io.github.cuihairu.redis.streaming.checkpoint.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.api.checkpoint.CheckpointCoordinator;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.storage.CheckpointStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-based implementation of CheckpointCoordinator.
 */
public class RedisCheckpointCoordinator implements CheckpointCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointCoordinator.class);

    private final CheckpointStorage storage;
    private final AtomicLong checkpointIdCounter;

    // Track pending checkpoints: checkpointId -> set of task IDs that acknowledged
    private final Map<Long, Map<String, Boolean>> pendingCheckpoints;

    // Configuration
    private final int requiredTaskAcks;
    private final long checkpointTimeout;

    public RedisCheckpointCoordinator(CheckpointStorage storage, int requiredTaskAcks) {
        this(storage, requiredTaskAcks, 60000); // Default 60s timeout
    }

    public RedisCheckpointCoordinator(CheckpointStorage storage, int requiredTaskAcks, long checkpointTimeout) {
        this.storage = storage;
        this.requiredTaskAcks = requiredTaskAcks;
        this.checkpointTimeout = checkpointTimeout;
        this.checkpointIdCounter = new AtomicLong(0);
        this.pendingCheckpoints = new ConcurrentHashMap<>();

        // Initialize counter from latest checkpoint
        try {
            Checkpoint latest = storage.getLatestCheckpoint();
            if (latest != null) {
                checkpointIdCounter.set(latest.getCheckpointId() + 1);
            }
        } catch (Exception e) {
            log.warn("Failed to load latest checkpoint", e);
        }
    }

    @Override
    public long triggerCheckpoint() {
        long checkpointId = checkpointIdCounter.getAndIncrement();
        long timestamp = System.currentTimeMillis();

        DefaultCheckpoint checkpoint = new DefaultCheckpoint(checkpointId, timestamp);

        try {
            // Store the checkpoint
            storage.storeCheckpoint(checkpoint);

            // Track as pending
            pendingCheckpoints.put(checkpointId, new ConcurrentHashMap<>());

            log.info("Triggered checkpoint {}", checkpointId);
            return checkpointId;

        } catch (Exception e) {
            log.error("Failed to trigger checkpoint {}", checkpointId, e);
            pendingCheckpoints.remove(checkpointId);
            return -1;
        }
    }

    @Override
    public void acknowledgeCheckpoint(long checkpointId, String taskId) {
        Map<String, Boolean> acks = pendingCheckpoints.get(checkpointId);

        if (acks == null) {
            log.warn("Received ack for unknown checkpoint {} from task {}", checkpointId, taskId);
            return;
        }

        acks.put(taskId, true);
        log.debug("Task {} acknowledged checkpoint {}", taskId, checkpointId);

        // Check if all required tasks have acknowledged
        if (acks.size() >= requiredTaskAcks) {
            completeCheckpoint(checkpointId);
        }
    }

    @Override
    public void completeCheckpoint(long checkpointId) {
        Map<String, Boolean> acks = pendingCheckpoints.remove(checkpointId);

        if (acks == null) {
            log.warn("Attempted to complete unknown checkpoint {}", checkpointId);
            return;
        }

        try {
            Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);
            if (checkpoint != null) {
                checkpoint.markCompleted();
                storage.storeCheckpoint(checkpoint);
                log.info("Completed checkpoint {} (acknowledged by {} tasks)", checkpointId, acks.size());
            }
        } catch (Exception e) {
            log.error("Failed to complete checkpoint {}", checkpointId, e);
        }
    }

    @Override
    public void restoreFromCheckpoint(long checkpointId) {
        try {
            Checkpoint checkpoint = storage.loadCheckpoint(checkpointId);

            if (checkpoint == null) {
                log.error("Cannot restore from checkpoint {}: not found", checkpointId);
                return;
            }

            if (!checkpoint.isCompleted()) {
                log.warn("Restoring from incomplete checkpoint {}", checkpointId);
            }

            log.info("Restoring from checkpoint {}", checkpointId);

            // Restore state from snapshot
            Checkpoint.StateSnapshot snapshot = checkpoint.getStateSnapshot();
            for (String key : snapshot.getKeys()) {
                Object value = snapshot.getState(key);
                log.debug("Restored state: {} = {}", key, value);
            }

            log.info("Successfully restored from checkpoint {}", checkpointId);

        } catch (Exception e) {
            log.error("Failed to restore from checkpoint {}", checkpointId, e);
        }
    }

    @Override
    public Checkpoint getLatestCheckpoint() {
        try {
            return storage.getLatestCheckpoint();
        } catch (Exception e) {
            log.error("Failed to get latest checkpoint", e);
            return null;
        }
    }

    @Override
    public Checkpoint getCheckpoint(long checkpointId) {
        try {
            return storage.loadCheckpoint(checkpointId);
        } catch (Exception e) {
            log.error("Failed to get checkpoint {}", checkpointId, e);
            return null;
        }
    }

    /**
     * Cleanup old checkpoints, keeping only the most recent ones
     *
     * @param keepCount Number of checkpoints to keep
     * @return Number of checkpoints deleted
     */
    public int cleanupOldCheckpoints(int keepCount) {
        try {
            int deleted = storage.cleanupOldCheckpoints(keepCount);
            log.info("Cleaned up {} old checkpoints, keeping {}", deleted, keepCount);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to cleanup old checkpoints", e);
            return 0;
        }
    }

    /**
     * Get the number of pending checkpoints
     */
    public int getPendingCheckpointCount() {
        return pendingCheckpoints.size();
    }

    /**
     * Close the coordinator and release resources
     */
    public void close() {
        pendingCheckpoints.clear();
        storage.close();
    }
}
