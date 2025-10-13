package io.github.cuihairu.redis.streaming.checkpoint.storage;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;

import java.util.List;

/**
 * CheckpointStorage provides persistence for checkpoints.
 */
public interface CheckpointStorage {

    /**
     * Store a checkpoint
     *
     * @param checkpoint The checkpoint to store
     */
    void storeCheckpoint(Checkpoint checkpoint) throws Exception;

    /**
     * Load a checkpoint by ID
     *
     * @param checkpointId The checkpoint ID
     * @return The checkpoint, or null if not found
     */
    Checkpoint loadCheckpoint(long checkpointId) throws Exception;

    /**
     * Get the latest checkpoint
     *
     * @return The latest checkpoint, or null if none exists
     */
    Checkpoint getLatestCheckpoint() throws Exception;

    /**
     * List all checkpoints, ordered by timestamp (newest first)
     *
     * @param limit Maximum number of checkpoints to return
     * @return List of checkpoints
     */
    List<Checkpoint> listCheckpoints(int limit) throws Exception;

    /**
     * Delete a checkpoint
     *
     * @param checkpointId The checkpoint ID to delete
     * @return true if deleted, false if not found
     */
    boolean deleteCheckpoint(long checkpointId) throws Exception;

    /**
     * Delete old checkpoints, keeping only the most recent ones
     *
     * @param keepCount Number of checkpoints to keep
     * @return Number of checkpoints deleted
     */
    int cleanupOldCheckpoints(int keepCount) throws Exception;

    /**
     * Close the storage and release resources
     */
    void close();
}
