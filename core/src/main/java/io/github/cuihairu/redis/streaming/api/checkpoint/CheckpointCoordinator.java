package io.github.cuihairu.redis.streaming.api.checkpoint;

/**
 * CheckpointCoordinator coordinates checkpoint operations across the streaming application.
 */
public interface CheckpointCoordinator {

    /**
     * Trigger a new checkpoint
     *
     * @return The checkpoint ID
     */
    long triggerCheckpoint();

    /**
     * Acknowledge a checkpoint from a task
     *
     * @param checkpointId The checkpoint ID
     * @param taskId The task ID
     */
    void acknowledgeCheckpoint(long checkpointId, String taskId);

    /**
     * Complete a checkpoint when all tasks have acknowledged
     *
     * @param checkpointId The checkpoint ID
     */
    void completeCheckpoint(long checkpointId);

    /**
     * Restore from a checkpoint
     *
     * @param checkpointId The checkpoint ID to restore from
     */
    void restoreFromCheckpoint(long checkpointId);

    /**
     * Get the latest completed checkpoint
     *
     * @return The latest checkpoint, or null if none exists
     */
    Checkpoint getLatestCheckpoint();

    /**
     * Get a specific checkpoint by ID
     *
     * @param checkpointId The checkpoint ID
     * @return The checkpoint, or null if not found
     */
    Checkpoint getCheckpoint(long checkpointId);
}
