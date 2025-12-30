package io.github.cuihairu.redis.streaming.runtime.internal;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class InMemoryKeyedStateStore<K> {

    private final Map<String, Map<K, Object>> valuesByState = new HashMap<>();
    private K currentKey;

    K currentKey() {
        return currentKey;
    }

    void setCurrentKey(K key) {
        this.currentKey = key;
    }

    Object get(String stateName, K key) {
        Map<K, Object> values = valuesByState.get(stateName);
        return values == null ? null : values.get(key);
    }

    void put(String stateName, K key, Object value) {
        valuesByState.computeIfAbsent(stateName, k -> new HashMap<>()).put(key, value);
    }

    void remove(String stateName, K key) {
        Map<K, Object> values = valuesByState.get(stateName);
        if (values != null) {
            values.remove(key);
        }
    }

    Map<String, Map<Object, Object>> snapshot() {
        Map<String, Map<Object, Object>> snapshot = new HashMap<>(valuesByState.size());
        for (Map.Entry<String, Map<K, Object>> entry : valuesByState.entrySet()) {
            snapshot.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return snapshot;
    }

    void restoreFromSnapshot(Object snapshot) {
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
