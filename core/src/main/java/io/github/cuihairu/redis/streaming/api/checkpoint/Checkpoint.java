package io.github.cuihairu.redis.streaming.api.checkpoint;

import java.io.Serializable;

/**
 * Checkpoint represents a consistent snapshot of the streaming application state.
 */
public interface Checkpoint extends Serializable {

    /**
     * Get the checkpoint ID
     */
    long getCheckpointId();

    /**
     * Get the timestamp when the checkpoint was created
     */
    long getTimestamp();

    /**
     * Get the state snapshot
     */
    StateSnapshot getStateSnapshot();

    /**
     * Check if the checkpoint is completed
     */
    boolean isCompleted();

    /**
     * Mark the checkpoint as completed
     */
    void markCompleted();

    /**
     * StateSnapshot holds the state data at a specific checkpoint
     */
    interface StateSnapshot extends Serializable {
        /**
         * Get state by key
         */
        <T> T getState(String key);

        /**
         * Put state by key
         */
        <T> void putState(String key, T value);

        /**
         * Get all state keys
         */
        Iterable<String> getKeys();
    }
}
