package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.ListState;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
import org.redisson.api.RList;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Keyed {@link ListState} on the runtime keyed store: one Redis List per current key at
 * {@code ...:state:{operatorId}:{stateName}:{key}}, elements JSON-serialized. The key is
 * registered in the {@code stateKeys} checkpoint index at first access and inherits the
 * state TTL; it snapshots as a LIST value. {@code update}/{@code addAll} run as one
 * REDIS_WRITE_ATOMIC batch (delete + addAll), so a dropped connection never leaves a
 * half-replaced list. Hot-key detection is hash-field based and
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

    /**
     * Redis key of this state for the current key. The batch paths in {@link #update}
     * and {@link #addAll} bypass {@link #list()}, so they keep the {@code stateKeys}
     * checkpoint index registration and the schema entry themselves.
     */
    private String itemKey() {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        String redisKey = store.stateItemKey(descriptor.getName(), store.stateFieldForKey(key));
        store.registerStateKey(redisKey);
        store.ensureSchemaForKey(redisKey, descriptor);
        return redisKey;
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
        String redisKey = itemKey();
        List<String> encoded = new ArrayList<>();
        if (values != null) {
            for (T v : values) {
                encoded.add(jsonFor(v));
            }
        }
        // MULTI/EXEC keeps the replacement atomic (B-38): the old clear()+add loop
        // destroyed the old state up front, so a connection dropped mid-way left the
        // key emptied or half-written. Readers see either the old list or the new one.
        RBatch batch = store.redissonClient().createBatch(
                BatchOptions.defaults().executionMode(BatchOptions.ExecutionMode.REDIS_WRITE_ATOMIC));
        batch.getList(redisKey, StringCodec.INSTANCE).deleteAsync();
        if (!encoded.isEmpty()) {
            batch.getList(redisKey, StringCodec.INSTANCE).addAllAsync(encoded);
        }
        batch.execute();
        store.recordKeyedStateWrite(descriptor.getName(), (System.nanoTime() - startNs) / 1_000_000);
    }

    @Override
    public void addAll(Iterable<T> values) {
        if (values == null) {
            return;
        }
        long startNs = System.nanoTime();
        List<String> encoded = new ArrayList<>();
        for (T v : values) {
            encoded.add(jsonFor(v));
        }
        if (encoded.isEmpty()) {
            return;
        }
        // One atomic batch (B-38): the per-element loop left a partial append behind
        // on connection drop, and a retry re-appended duplicates.
        String redisKey = itemKey();
        RBatch batch = store.redissonClient().createBatch(
                BatchOptions.defaults().executionMode(BatchOptions.ExecutionMode.REDIS_WRITE_ATOMIC));
        batch.getList(redisKey, StringCodec.INSTANCE).addAllAsync(encoded);
        batch.execute();
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
