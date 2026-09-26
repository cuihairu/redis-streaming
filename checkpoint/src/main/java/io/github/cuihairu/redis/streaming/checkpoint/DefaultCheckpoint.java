package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Default implementation of Checkpoint.
 */
public class DefaultCheckpoint implements Checkpoint, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Snapshot version written by every checkpoint created with the current library
     * (B-13). Checkpoints persisted before versioning existed deserialize without this
     * marker and report {@link #LEGACY_SNAPSHOT_VERSION}.
     */
    public static final int CURRENT_SNAPSHOT_VERSION = 1;

    /** Version reported by checkpoints that carry no version marker. */
    public static final int LEGACY_SNAPSHOT_VERSION = 0;

    private final long checkpointId;
    private final long timestamp;
    private final StateSnapshotImpl stateSnapshot;
    private volatile boolean completed;
    // Field initializer keeps LEGACY so checkpoints persisted before versioning deserialize
    // as legacy; the constructor stamps every in-memory creation with the current version.
    private int snapshotVersion = LEGACY_SNAPSHOT_VERSION;

    public DefaultCheckpoint(long checkpointId, long timestamp) {
        this.checkpointId = checkpointId;
        this.timestamp = timestamp;
        this.stateSnapshot = new StateSnapshotImpl();
        this.completed = false;
        this.snapshotVersion = CURRENT_SNAPSHOT_VERSION;
    }

    @Override
    public long getCheckpointId() {
        return checkpointId;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public StateSnapshot getStateSnapshot() {
        return stateSnapshot;
    }

    @Override
    public boolean isCompleted() {
        return completed;
    }

    @Override
    public void markCompleted() {
        this.completed = true;
    }

    @Override
    public int getSnapshotVersion() {
        return snapshotVersion;
    }

    @Override
    public String toString() {
        return "DefaultCheckpoint{" +
                "id=" + checkpointId +
                ", timestamp=" + timestamp +
                ", completed=" + completed +
                '}';
    }

    /**
     * Implementation of StateSnapshot
     */
    static class StateSnapshotImpl implements StateSnapshot, Serializable {
        private static final long serialVersionUID = 1L;

        private static final ObjectMapper STATE_MAPPER = new ObjectMapper();

        private final Map<String, Object> stateMap = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getState(String key) {
            return (T) stateMap.get(key);
        }

        /**
         * B-13: the raw {@code getState} cast is only checked when the caller uses the
         * value, so a shape that lost its type in a codec round-trip surfaces as a
         * ClassCastException far from the read. This typed variant converts a decoded
         * shape (e.g. a map of fields) to the requested type right here, or fails with a
         * message naming the key, the stored type and the requested type.
         */
        @Override
        public <T> T getState(String key, Class<T> type) {
            Object value = stateMap.get(key);
            if (value == null || type.isInstance(value)) {
                return type.cast(value);
            }
            try {
                return STATE_MAPPER.convertValue(value, type);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("State '" + key + "' is "
                        + value.getClass().getName() + " and cannot be provided as "
                        + type.getName(), e);
            }
        }

        @Override
        public <T> void putState(String key, T value) {
            stateMap.put(key, value);
        }

        @Override
        public Iterable<String> getKeys() {
            return stateMap.keySet();
        }

        public int size() {
            return stateMap.size();
        }

        public void clear() {
            stateMap.clear();
        }
    }
}
