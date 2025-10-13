package io.github.cuihairu.redis.streaming.state.redis;

import io.github.cuihairu.redis.streaming.api.state.MapState;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.Map;

/**
 * Redis-based implementation of MapState.
 *
 * @param <K> The type of keys
 * @param <V> The type of values
 */
public class RedisMapState<K, V> implements MapState<K, V> {

    private final RedissonClient redisson;
    private final String key;
    private final Class<K> keyType;
    private final Class<V> valueType;

    public RedisMapState(RedissonClient redisson, String key, Class<K> keyType, Class<V> valueType) {
        this.redisson = redisson;
        this.key = key;
        this.keyType = keyType;
        this.valueType = valueType;
    }

    private RMap<K, V> getMap() {
        return redisson.getMap(key);
    }

    @Override
    public V get(K key) {
        return getMap().get(key);
    }

    @Override
    public void put(K key, V value) {
        getMap().put(key, value);
    }

    @Override
    public void remove(K key) {
        getMap().remove(key);
    }

    @Override
    public boolean contains(K key) {
        return getMap().containsKey(key);
    }

    @Override
    public Iterable<Map.Entry<K, V>> entries() {
        return getMap().entrySet();
    }

    @Override
    public Iterable<K> keys() {
        return getMap().keySet();
    }

    @Override
    public Iterable<V> values() {
        return getMap().values();
    }

    @Override
    public boolean isEmpty() {
        return getMap().isEmpty();
    }

    @Override
    public void clear() {
        getMap().clear();
    }

    public String getKey() {
        return key;
    }
}
