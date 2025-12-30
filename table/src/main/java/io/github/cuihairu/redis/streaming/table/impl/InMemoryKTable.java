package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.table.KGroupedTable;
import io.github.cuihairu.redis.streaming.table.KTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * In-memory implementation of KTable for testing and simple use cases.
 *
 * This implementation maintains the current state of the table in memory
 * and supports basic table operations.
 *
 * @param <K> The type of the key
 * @param <V> The type of the value
 */
public class InMemoryKTable<K, V> implements KTable<K, V> {

    private static final long serialVersionUID = 1L;

    private final Map<K, V> state;

    public InMemoryKTable() {
        this.state = new ConcurrentHashMap<>();
    }

    public InMemoryKTable(Map<K, V> state) {
        this.state = new ConcurrentHashMap<>(state);
    }

    /**
     * Update or insert a key-value pair
     */
    public void put(K key, V value) {
        if (value == null) {
            state.remove(key);
        } else {
            state.put(key, value);
        }
    }

    /**
     * Get the value for a key
     */
    public V get(K key) {
        return state.get(key);
    }

    /**
     * Get all entries in the table
     */
    public Map<K, V> getState() {
        return new ConcurrentHashMap<>(state);
    }

    /**
     * Get the number of entries in the table
     */
    public int size() {
        return state.size();
    }

    /**
     * Clear all entries from the table
     */
    public void clear() {
        state.clear();
    }

    @Override
    public <VR> KTable<K, VR> mapValues(Function<V, VR> mapper) {
        InMemoryKTable<K, VR> result = new InMemoryKTable<>();
        state.forEach((key, value) -> {
            VR newValue = mapper.apply(value);
            result.put(key, newValue);
        });
        return result;
    }

    @Override
    public <VR> KTable<K, VR> mapValues(BiFunction<K, V, VR> mapper) {
        InMemoryKTable<K, VR> result = new InMemoryKTable<>();
        state.forEach((key, value) -> {
            VR newValue = mapper.apply(key, value);
            result.put(key, newValue);
        });
        return result;
    }

    @Override
    public KTable<K, V> filter(BiFunction<K, V, Boolean> predicate) {
        InMemoryKTable<K, V> result = new InMemoryKTable<>();
        state.forEach((key, value) -> {
            if (predicate.apply(key, value)) {
                result.put(key, value);
            }
        });
        return result;
    }

    @Override
    public <VO, VR> KTable<K, VR> join(KTable<K, VO> other, BiFunction<V, VO, VR> joiner) {
        Map<K, VO> otherState = resolveJoinState(other);
        InMemoryKTable<K, VR> result = new InMemoryKTable<>();

        state.forEach((key, value) -> {
            VO otherValue = otherState.get(key);
            if (otherValue != null) {
                VR joinedValue = joiner.apply(value, otherValue);
                result.put(key, joinedValue);
            }
        });

        return result;
    }

    @Override
    public <VO, VR> KTable<K, VR> leftJoin(KTable<K, VO> other, BiFunction<V, VO, VR> joiner) {
        Map<K, VO> otherState = resolveJoinState(other);
        InMemoryKTable<K, VR> result = new InMemoryKTable<>();

        state.forEach((key, value) -> {
            VO otherValue = otherState.get(key);
            VR joinedValue = joiner.apply(value, otherValue);
            result.put(key, joinedValue);
        });

        return result;
    }

    private <VO> Map<K, VO> resolveJoinState(KTable<K, VO> other) {
        if (other instanceof InMemoryKTable<?, ?>) {
            @SuppressWarnings("unchecked")
            InMemoryKTable<K, VO> otherTable = (InMemoryKTable<K, VO>) other;
            return otherTable.getState();
        }
        if (other instanceof RedisKTable<?, ?>) {
            @SuppressWarnings("unchecked")
            RedisKTable<K, VO> otherTable = (RedisKTable<K, VO>) other;
            return otherTable.getState();
        }
        throw new UnsupportedOperationException("Can only join with InMemoryKTable or RedisKTable");
    }

    @Override
    public DataStream<KeyValue<K, V>> toStream() {
        List<KeyValue<K, V>> out = new ArrayList<>(state.size());
        state.forEach((k, v) -> out.add(KeyValue.of(k, v)));
        return StreamExecutionEnvironment.getExecutionEnvironment().fromCollection(out);
    }

    @Override
    public <KR> KGroupedTable<KR, V> groupBy(Function<KeyValue<K, V>, KR> keySelector) {
        return new InMemoryKGroupedTable<>(this, keySelector);
    }

    @Override
    public String toString() {
        return "InMemoryKTable{" + "size=" + state.size() + '}';
    }
}
