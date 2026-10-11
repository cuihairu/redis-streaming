package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.MapState;
import org.redisson.api.RMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keyed {@link MapState} on the runtime keyed store: one Redis Hash per current key, fields
 * are the JSON-serialized map keys, values the JSON-serialized values. The hash is registered
 * in the {@code stateKeys} checkpoint index at first access, inherits the state TTL and the
 * hot-key policy through {@code touch} (same as value state), and snapshots as a MAP value.
 */
final class RedisKeyedMapState<K, MK, MV> implements MapState<MK, MV> {

    private final RedisKeyedStateStore<K> store;
    private final String name;
    private final Class<MK> keyType;
    private final Class<MV> valueType;
    private final ObjectMapper objectMapper;

    RedisKeyedMapState(RedisKeyedStateStore<K> store, String name, Class<MK> keyType,
                       Class<MV> valueType, ObjectMapper objectMapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.name = Objects.requireNonNull(name, "name");
        this.keyType = Objects.requireNonNull(keyType, "keyType");
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    private String fieldFor(MK key) {
        try {
            return objectMapper.writeValueAsString(key);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize state map key for " + name, e);
        }
    }

    private MK keyFor(String field) {
        try {
            return objectMapper.readValue(field, keyType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize state map key for " + name, e);
        }
    }

    private MV valueFor(String json) {
        try {
            return objectMapper.readValue(json, valueType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize state value for " + name, e);
        }
    }

    private String jsonFor(MV value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize state value for " + name, e);
        }
    }

    private record KeyedRef(String redisKey, RMap<String, String> map, String field) {
    }

    private KeyedRef ref() {
        K key = store.currentKey();
        if (key == null) {
            throw new IllegalStateException("No current key is set for keyed state access");
        }
        String field = store.stateFieldForKey(key);
        RedisKeyedStateStore.StateMapRef r = store.stateMapRefForKey(name, field);
        store.ensureSchema(r, schemaDescriptor());
        return new KeyedRef(r.redisKey(), r.map(), field);
    }

    /**
     * Schema registration reuses the value-state format ("type|version") with the map's
     * value type: the payload schema that matters for evolution is the entry value type.
     */
    private io.github.cuihairu.redis.streaming.api.state.StateDescriptor<MV> schemaDescriptor() {
        return new io.github.cuihairu.redis.streaming.api.state.StateDescriptor<>(name, valueType);
    }

    @Override
    public MV get(MK key) {
        Objects.requireNonNull(key, "key");
        KeyedRef r = ref();
        long startNs = System.nanoTime();
        String json = r.map().get(fieldFor(key));
        store.recordKeyedStateRead(name, (System.nanoTime() - startNs) / 1_000_000);
        return json == null ? null : valueFor(json);
    }

    @Override
    public void put(MK key, MV value) {
        Objects.requireNonNull(key, "key");
        KeyedRef r = ref();
        RMap<String, String> map = r.map();
        long startNs = System.nanoTime();
        if (value == null) {
            map.remove(fieldFor(key));
            store.touch(r.redisKey(), name, map);
            store.recordKeyedStateDelete(name);
            store.recordKeyedStateWrite(name, (System.nanoTime() - startNs) / 1_000_000);
            return;
        }
        // serialization failures throw before the map is touched; hot-key FAIL_FAST from
        // touch propagates unwrapped so the MQ retry/DLQ machinery sees the real cause
        map.put(fieldFor(key), jsonFor(value));
        store.touch(r.redisKey(), name, map);
        store.recordKeyedStateWrite(name, (System.nanoTime() - startNs) / 1_000_000);
    }

    @Override
    public void remove(MK key) {
        Objects.requireNonNull(key, "key");
        KeyedRef r = ref();
        RMap<String, String> map = r.map();
        long startNs = System.nanoTime();
        map.remove(fieldFor(key));
        store.touch(r.redisKey(), name, map);
        store.recordKeyedStateDelete(name);
        store.recordKeyedStateWrite(name, (System.nanoTime() - startNs) / 1_000_000);
    }

    @Override
    public boolean contains(MK key) {
        Objects.requireNonNull(key, "key");
        KeyedRef r = ref();
        long startNs = System.nanoTime();
        boolean exists = r.map().containsKey(fieldFor(key));
        store.recordKeyedStateRead(name, (System.nanoTime() - startNs) / 1_000_000);
        return exists;
    }

    @Override
    public Iterable<Map.Entry<MK, MV>> entries() {
        KeyedRef r = ref();
        long startNs = System.nanoTime();
        Map<String, String> all = r.map().readAllMap();
        store.recordKeyedStateRead(name, (System.nanoTime() - startNs) / 1_000_000);
        Map<MK, MV> out = new HashMap<>();
        if (all != null) {
            for (Map.Entry<String, String> e : all.entrySet()) {
                out.put(keyFor(e.getKey()), valueFor(e.getValue()));
            }
        }
        return out.entrySet();
    }

    @Override
    public Iterable<MK> keys() {
        KeyedRef r = ref();
        long startNs = System.nanoTime();
        Map<String, String> all = r.map().readAllMap();
        store.recordKeyedStateRead(name, (System.nanoTime() - startNs) / 1_000_000);
        List<MK> out = new ArrayList<>();
        if (all != null) {
            for (String field : all.keySet()) {
                out.add(keyFor(field));
            }
        }
        return out;
    }

    private Iterable<MK> keysDirect() {
        KeyedRef r = ref();
        long startNs = System.nanoTime();
        Map<String, String> all = r.map().readAllMap();
        store.recordKeyedStateRead(name, (System.nanoTime() - startNs) / 1_000_000);
        List<MK> out = new ArrayList<>();
        if (all != null) {
            for (String field : all.keySet()) {
                out.add(keyFor(field));
            }
        }
        return out;
    }

    @Override
    public Iterable<MV> values() {
        KeyedRef r = ref();
        long startNs = System.nanoTime();
        Map<String, String> all = r.map().readAllMap();
        store.recordKeyedStateRead(name, (System.nanoTime() - startNs) / 1_000_000);
        List<MV> out = new ArrayList<>();
        if (all != null) {
            for (String json : all.values()) {
                out.add(valueFor(json));
            }
        }
        return out;
    }

    @Override
    public boolean isEmpty() {
        KeyedRef r = ref();
        long startNs = System.nanoTime();
        boolean empty = r.map().isEmpty();
        store.recordKeyedStateRead(name, (System.nanoTime() - startNs) / 1_000_000);
        return empty;
    }

    @Override
    public void clear() {
        KeyedRef r = ref();
        long startNs = System.nanoTime();
        r.map().delete();
        store.recordKeyedStateDelete(name);
        store.recordKeyedStateWrite(name, (System.nanoTime() - startNs) / 1_000_000);
    }
}
