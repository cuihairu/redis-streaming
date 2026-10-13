package io.github.cuihairu.redis.streaming.runtime.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class InMemoryKeyedStateStore<K> {

    // Mutation and snapshot paths synchronize on the coordinator's checkpoint lock once the store
    // registers (see bindCheckpointLock), so a checkpoint snapshot is mutually exclusive with state
    // writes and with a restore. Before registration the store guards itself with its own monitor —
    // enough for construction-time use (put-before-register, restore replay).
    private final Object selfLock = new Object();
    private volatile Object mutationLock = selfLock;

    private final Map<String, Map<K, Object>> valuesByState = new HashMap<>();
    private K currentKey;

    void bindCheckpointLock(Object checkpointLock) {
        if (checkpointLock != null) {
            this.mutationLock = checkpointLock;
        }
    }

    private Object lock() {
        return mutationLock;
    }

    /**
     * The monitor a record body must hold across all of its state writes (per-record barrier):
     * the same lock every mutation and snapshot synchronizes on, so a checkpoint triggered
     * mid-record blocks until the record finishes. Reentrant, so the store's own per-write
     * locking is a no-op inside the fence.
     */
    Object fenceLock() {
        return mutationLock;
    }

    K currentKey() {
        synchronized (lock()) {
            return currentKey;
        }
    }

    void setCurrentKey(K key) {
        synchronized (lock()) {
            this.currentKey = key;
        }
    }

    Object get(String stateName, K key) {
        synchronized (lock()) {
            Map<K, Object> values = valuesByState.get(stateName);
            return values == null ? null : values.get(key);
        }
    }

    void put(String stateName, K key, Object value) {
        synchronized (lock()) {
            valuesByState.computeIfAbsent(stateName, k -> new HashMap<>()).put(key, value);
        }
    }

    void remove(String stateName, K key) {
        synchronized (lock()) {
            Map<K, Object> values = valuesByState.get(stateName);
            if (values != null) {
                values.remove(key);
            }
        }
    }

    Map<String, Map<Object, Object>> snapshot() {
        synchronized (lock()) {
            Map<String, Map<Object, Object>> snapshot = new HashMap<>(valuesByState.size());
            for (Map.Entry<String, Map<K, Object>> entry : valuesByState.entrySet()) {
                snapshot.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
            return snapshot;
        }
    }

    void restoreFromSnapshot(Object snapshot) {
        synchronized (lock()) {
            if (snapshot == null) {
                valuesByState.clear();
                return;
            }
            if (!(snapshot instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Unsupported snapshot type: " + snapshot.getClass().getName());
            }

            valuesByState.clear();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String stateName)) {
                    continue;
                }
                Object stateValue = entry.getValue();
                if (!(stateValue instanceof Map<?, ?> stateMapRaw)) {
                    valuesByState.put(stateName, Collections.emptyMap());
                    continue;
                }
                Map<K, Object> restored = new HashMap<>();
                for (Map.Entry<?, ?> stateEntry : stateMapRaw.entrySet()) {
                    @SuppressWarnings("unchecked")
                    K key = (K) stateEntry.getKey();
                    restored.put(key, stateEntry.getValue());
                }
                valuesByState.put(stateName, restored);
            }
        }
    }
}
