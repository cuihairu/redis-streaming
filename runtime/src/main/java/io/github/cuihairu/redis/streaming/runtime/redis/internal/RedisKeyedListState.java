package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.ListState;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import org.redisson.api.RList;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Keyed {@link ListState} on the runtime keyed store: one Redis List per current key at
 * {@code ...:state:{operatorId}:{stateName}:{key}}, elements JSON-serialized. The key is
 * registered in the {@code stateKeys} checkpoint index at first access and inherits the
 * state TTL; it snapshots as a LIST value. Hot-key detection is hash-field based and
 * therefore does not apply to list state — a large list is one big key by construction.
 */
final class RedisKeyedListState<K, T> implements ListState<T> {

    private final RedisKeyedStateStore<K> store;
    private final StateDescriptor<T> descriptor;
    private final ObjectMapper objectMapper;

    RedisKeyedListState(RedisKeyedStateStore<K> store, StateDescriptor<T> descriptor, ObjectMapper objectMapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    private RList<String> list() {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        RList<String> list = store.stateListForKey(descriptor.getName(), store.stateFieldForKey(key));
        store.ensureSchemaForKey(store.stateItemKey(descriptor.getName(), store.stateFieldForKey(key)), descriptor);
        return list;
    }

    private String jsonFor(T value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize state value for " + descriptor.getName(), e);
        }
    }

    private T valueFor(String json) {
        try {
            return objectMapper.readValue(json, descriptor.getType());
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize state value for " + descriptor.getName(), e);
        }
    }

    @Override
    public void add(T value) {
        long startNs = System.nanoTime();
        list().add(jsonFor(value));
        store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
    }

    @Override
    public Iterable<T> get() {
        long startNs = System.nanoTime();
        java.util.List<String> all = list().readAll();
        store.recordKeyedStateRead(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
        ArrayList<T> out = new ArrayList<>(all.size());
        for (String json : all) {
            out.add(valueFor(json));
        }
        return out;
    }

    @Override
    public void update(Iterable<T> values) {
        long startNs = System.nanoTime();
        RList<String> list = list();
        list.clear();
        if (values != null) {
            for (T v : values) {
                list.add(jsonFor(v));
            }
        }
        store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
    }

    @Override
    public void addAll(Iterable<T> values) {
        if (values == null) {
            return;
        }
        long startNs = System.nanoTime();
        RList<String> list = list();
        for (T v : values) {
            list.add(jsonFor(v));
        }
        store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
    }

    @Override
    public void clear() {
        long startNs = System.nanoTime();
        list().clear();
        store.recordKeyedStateDelete(descriptor.getName());
        store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
    }
}
