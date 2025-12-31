package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import org.redisson.api.RMap;

import java.util.Objects;

final class RedisKeyedValueState<K, T> implements ValueState<T> {

    private final RedisKeyedStateStore<K> store;
    private final StateDescriptor<T> descriptor;
    private final ObjectMapper objectMapper;

    RedisKeyedValueState(RedisKeyedStateStore<K> store, StateDescriptor<T> descriptor, ObjectMapper objectMapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public T value() {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        String field = serializeKey(key);
        RMap<String, String> map = store.stateMap(descriptor.getName());
        String json = map.get(field);
        if (json == null) {
            return descriptor.getDefaultValue();
        }
        try {
            return objectMapper.readValue(json, descriptor.getType());
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize state value for " + descriptor.getName(), e);
        }
    }

    @Override
    public void update(T value) {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        String field = serializeKey(key);
        RMap<String, String> map = store.stateMap(descriptor.getName());
        if (value == null) {
            map.remove(field);
            return;
        }
        try {
            map.put(field, objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize state value for " + descriptor.getName(), e);
        }
    }

    @Override
    public void clear() {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        store.stateMap(descriptor.getName()).remove(serializeKey(key));
    }

    private String serializeKey(K key) {
        try {
            if (key instanceof String s) {
                return "s:" + s;
            }
            if (key instanceof Number n) {
                return "n:" + n;
            }
            return "j:" + objectMapper.writeValueAsString(key);
        } catch (Exception e) {
            return "t:" + String.valueOf(key);
        }
    }
}

