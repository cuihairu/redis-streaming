package io.github.cuihairu.redis.streaming.state.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.ListState;
import io.github.cuihairu.redis.streaming.api.state.MapState;
import io.github.cuihairu.redis.streaming.api.state.SetState;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisKeyedStateStore;
import io.github.cuihairu.redis.streaming.state.backend.StateBackend;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Standalone Redis {@link StateBackend}, implemented as a thin adapter over the runtime
 * keyed state store — the single state implementation since the state unification
 * (docs/State-Unification-Design.md). The backend owns one store pinned to a fixed solo
 * identity and sets the current key around every operation, so the unkeyed
 * {@code StateBackend} contract keeps working while state lands in the standard keyed
 * layout: state keys live under {@code {keyPrefix}:{jobName}:...}, are registered in the
 * job's {@code stateKeys} index and {@code stateSchema} registry, and are therefore
 * visible to the runtime checkpoint snapshot/restore machinery.
 *
 * <p>Keys and codec are incompatible with the pre-unification implementation (flat
 * RBucket/RMap/RList/RSet keys at {@code keyPrefix + name} with the Redisson default
 * codec): this backend does not read old data. See docs/state.md for the migration table.
 */
public class RedisStateBackend implements StateBackend {

    /** Default state key prefix, matching the {@code RedisRuntimeConfig} default. */
    public static final String DEFAULT_KEY_PREFIX = "streaming:runtime";

    /** Fixed current key the adapter pins around every operation (hash field {@code s:solo}). */
    private static final String SOLO_KEY = "solo";

    /** Fixed topic / consumer-group / operator identity in the standalone key layout. */
    private static final String SOLO = "standalone";

    private final RedissonClient redisson;
    private final String keyPrefix;
    private final RedisKeyedStateStore<String> store;

    public RedisStateBackend(RedissonClient redisson) {
        this(redisson, "default");
    }

    public RedisStateBackend(RedissonClient redisson, String jobName) {
        this(redisson, jobName, DEFAULT_KEY_PREFIX);
    }

    public RedisStateBackend(RedissonClient redisson, String jobName, String keyPrefix) {
        this.redisson = redisson;
        this.keyPrefix = keyPrefix;
        this.store = new RedisKeyedStateStore<>(
                redisson,
                new ObjectMapper(),
                keyPrefix,
                jobName,
                SOLO,
                SOLO,
                SOLO,
                Duration.ZERO, // no TTL unless the owning pipeline configures one
                0,             // size reporting off
                1,             // no sharding
                0,             // hot-key detection off
                Duration.ZERO,
                RedisRuntimeConfig.HotKeyPolicy.LOG_ONLY,
                0,
                true,
                RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
    }

    @Override
    public <T> ValueState<T> createValueState(StateDescriptor<T> descriptor) {
        ValueState<T> delegate = store.getValueState(descriptor);
        return new ValueState<>() {
            @Override
            public T value() {
                return solo(delegate::value);
            }

            @Override
            public void update(T value) {
                solo(() -> delegate.update(value));
            }

            @Override
            public void clear() {
                solo(delegate::clear);
            }
        };
    }

    @Override
    public <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType) {
        MapState<K, V> delegate = store.getMapState(name, keyType, valueType);
        return new MapState<>() {
            @Override
            public V get(K key) {
                return solo(() -> delegate.get(key));
            }

            @Override
            public void put(K key, V value) {
                solo(() -> delegate.put(key, value));
            }

            @Override
            public void remove(K key) {
                solo(() -> delegate.remove(key));
            }

            @Override
            public boolean contains(K key) {
                return solo(() -> delegate.contains(key));
            }

            @Override
            public Iterable<Map.Entry<K, V>> entries() {
                return solo(delegate::entries);
            }

            @Override
            public Iterable<K> keys() {
                return solo(delegate::keys);
            }

            @Override
            public Iterable<V> values() {
                return solo(delegate::values);
            }

            @Override
            public boolean isEmpty() {
                return solo(delegate::isEmpty);
            }

            @Override
            public void clear() {
                solo(delegate::clear);
            }
        };
    }

    @Override
    public <T> ListState<T> createListState(StateDescriptor<T> descriptor) {
        ListState<T> delegate = store.getListState(descriptor);
        return new ListState<>() {
            @Override
            public void add(T value) {
                solo(() -> delegate.add(value));
            }

            @Override
            public Iterable<T> get() {
                return solo(delegate::get);
            }

            @Override
            public void update(Iterable<T> values) {
                solo(() -> delegate.update(values));
            }

            @Override
            public void addAll(Iterable<T> values) {
                solo(() -> delegate.addAll(values));
            }

            @Override
            public void clear() {
                solo(delegate::clear);
            }
        };
    }

    @Override
    public <T> SetState<T> createSetState(StateDescriptor<T> descriptor) {
        SetState<T> delegate = store.getSetState(descriptor);
        return new SetState<>() {
            @Override
            public boolean add(T value) {
                return solo(() -> delegate.add(value));
            }

            @Override
            public boolean remove(T value) {
                return solo(() -> delegate.remove(value));
            }

            @Override
            public boolean contains(T value) {
                return solo(() -> delegate.contains(value));
            }

            @Override
            public Iterable<T> get() {
                return solo(delegate::get);
            }

            @Override
            public boolean isEmpty() {
                return solo(delegate::isEmpty);
            }

            @Override
            public int size() {
                return solo(delegate::size);
            }

            @Override
            public void clear() {
                solo(delegate::clear);
            }
        };
    }

    @Override
    public void close() {
        // RedissonClient lifecycle is managed externally; the store holds no resources
    }

    public RedissonClient getRedisson() {
        return redisson;
    }

    /** State key prefix — the namespace root: state keys start with {@code keyPrefix + ":" + jobName}. */
    public String getKeyPrefix() {
        return keyPrefix;
    }

    /** Pin the solo identity for the duration of one delegated operation. */
    private void solo(Runnable op) {
        store.setCurrentKey(SOLO_KEY);
        store.setCurrentPartitionId(0);
        try {
            op.run();
        } finally {
            store.clearCurrentKey();
            store.clearCurrentPartitionId();
        }
    }

    private <V> V solo(Supplier<V> op) {
        store.setCurrentKey(SOLO_KEY);
        store.setCurrentPartitionId(0);
        try {
            return op.get();
        } finally {
            store.clearCurrentKey();
            store.clearCurrentPartitionId();
        }
    }
}
