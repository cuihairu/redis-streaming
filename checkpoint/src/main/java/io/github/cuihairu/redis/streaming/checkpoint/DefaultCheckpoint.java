package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Default implementation of Checkpoint.
 */
public class DefaultCheckpoint implements Checkpoint, Serializable {

    private static final long serialVersionUID = 1L;

    private final long checkpointId;
    private final long timestamp;
    private final StateSnapshotImpl stateSnapshot;
    private volatile boolean completed;

    public DefaultCheckpoint(long checkpointId, long timestamp) {
        this.checkpointId = checkpointId;
        this.timestamp = timestamp;
        this.stateSnapshot = new StateSnapshotImpl();
        this.completed = false;
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

        private final Map<String, Object> stateMap = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getState(String key) {
            return (T) stateMap.get(key);
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
