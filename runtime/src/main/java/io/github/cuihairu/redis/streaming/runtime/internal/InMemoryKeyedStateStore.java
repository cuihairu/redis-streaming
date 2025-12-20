package io.github.cuihairu.redis.streaming.runtime.internal;

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
}

