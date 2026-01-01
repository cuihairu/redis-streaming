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
        String field = store.stateFieldForKey(key);
        RedisKeyedStateStore.StateMapRef ref = store.stateMapRef(descriptor.getName(), field);
        store.ensureSchema(ref, descriptor);
        RMap<String, String> map = ref.map();
        long startNs = System.nanoTime();
        String json = map.get(field);
        store.recordKeyedStateRead(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
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
        String field = store.stateFieldForKey(key);
        RedisKeyedStateStore.StateMapRef ref = store.stateMapRef(descriptor.getName(), field);
        store.ensureSchema(ref, descriptor);
        RMap<String, String> map = ref.map();
        if (value == null) {
            long startNs = System.nanoTime();
            map.remove(field);
            store.touch(ref.redisKey(), descriptor.getName(), map);
            store.recordKeyedStateDelete(descriptor.getName());
            store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
            return;
        }
        try {
            long startNs = System.nanoTime();
            map.put(field, objectMapper.writeValueAsString(value));
            store.touch(ref.redisKey(), descriptor.getName(), map);
            store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
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
        String field = store.stateFieldForKey(key);
        RedisKeyedStateStore.StateMapRef ref = store.stateMapRef(descriptor.getName(), field);
        store.ensureSchema(ref, descriptor);
        RMap<String, String> map = ref.map();
        long startNs = System.nanoTime();
        map.remove(field);
        store.touch(ref.redisKey(), descriptor.getName(), map);
        store.recordKeyedStateDelete(descriptor.getName());
        store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
    }
}
