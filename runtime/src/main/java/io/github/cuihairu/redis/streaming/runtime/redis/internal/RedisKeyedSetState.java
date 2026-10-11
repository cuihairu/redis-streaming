package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.SetState;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import org.redisson.api.RSet;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Keyed {@link SetState} on the runtime keyed store: one Redis Set per current key at
 * {@code ...:state:{operatorId}:{stateName}:{key}}, members JSON-serialized. The key is
 * registered in the {@code stateKeys} checkpoint index at first access and inherits the
 * state TTL; it snapshots as a SET value. Hot-key detection is hash-field based and
 * therefore does not apply to set state — a large set is one big key by construction.
 */
final class RedisKeyedSetState<K, T> implements SetState<T> {

    private final RedisKeyedStateStore<K> store;
    private final StateDescriptor<T> descriptor;
    private final ObjectMapper objectMapper;

    RedisKeyedSetState(RedisKeyedStateStore<K> store, StateDescriptor<T> descriptor, ObjectMapper objectMapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    private RSet<String> set() {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        RSet<String> set = store.stateSetForKey(descriptor.getName(), store.stateFieldForKey(key));
        store.ensureSchemaForKey(store.stateItemKey(descriptor.getName(), store.stateFieldForKey(key)), descriptor);
        return set;
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
    public boolean add(T value) {
        long startNs = System.nanoTime();
        boolean added = set().add(jsonFor(value));
        store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
        return added;
    }

    @Override
    public boolean remove(T value) {
        long startNs = System.nanoTime();
        boolean removed = set().remove(jsonFor(value));
        store.recordKeyedStateDelete(descriptor.getName());
        store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
        return removed;
    }

    @Override
    public boolean contains(T value) {
        long startNs = System.nanoTime();
        boolean exists = set().contains(jsonFor(value));
        store.recordKeyedStateRead(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
        return exists;
    }

    @Override
    public Iterable<T> get() {
        long startNs = System.nanoTime();
        java.util.Set<String> all = set().readAll();
        store.recordKeyedStateRead(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
        ArrayList<T> out = new ArrayList<>(all.size());
        for (String json : all) {
            out.add(valueFor(json));
        }
        return out;
    }

    @Override
    public boolean isEmpty() {
        long startNs = System.nanoTime();
        boolean empty = set().isEmpty();
        store.recordKeyedStateRead(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
        return empty;
    }

    @Override
    public int size() {
        long startNs = System.nanoTime();
        int size = set().size();
        store.recordKeyedStateRead(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
        return size;
    }

    @Override
    public void clear() {
        long startNs = System.nanoTime();
        set().delete();
        store.recordKeyedStateDelete(descriptor.getName());
        store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
    }
}
