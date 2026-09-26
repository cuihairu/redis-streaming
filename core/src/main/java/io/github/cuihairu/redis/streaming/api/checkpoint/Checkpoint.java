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
     * Get the snapshot format version of this checkpoint (B-13). Version 0 means the
     * checkpoint was written before versioning existed and carries no version marker;
     * readers must fall back to legacy interpretation. Implementations written with the
     * current library return {@code 1}.
     */
    default int getSnapshotVersion() {
        return 0;
    }

    /**
     * StateSnapshot holds the state data at a specific checkpoint
     */
    interface StateSnapshot extends Serializable {
        /**
         * Get state by key
         */
        <T> T getState(String key);

        /**
         * Get state by key with an expected type. Unlike {@link #getState(String)}, the
         * result is checked at the call site: a value of an incompatible type fails here
         * with a descriptive exception instead of a distant ClassCastException (B-13).
         * Implementations backed by a JSON store may also accept a decoded form (e.g. a
         * map of fields) and convert it to the requested type.
         *
         * @param key the state key
         * @param type the expected type
         * @return the value, or null if the key is absent
         * @throws IllegalStateException if the stored value cannot be provided as {@code type}
         */
        default <T> T getState(String key, Class<T> type) {
            T value = getState(key);
            if (value != null && !type.isInstance(value)) {
                throw new IllegalStateException("State '" + key + "' is "
                        + value.getClass().getName() + ", expected " + type.getName());
            }
            return value;
        }

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
