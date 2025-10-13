package io.github.cuihairu.redis.streaming.state.redis;

import io.github.cuihairu.redis.streaming.api.state.*;
import io.github.cuihairu.redis.streaming.state.backend.StateBackend;
import org.redisson.api.RedissonClient;

/**
 * Redis-based implementation of StateBackend.
 */
public class RedisStateBackend implements StateBackend {

    private final RedissonClient redisson;
    private final String keyPrefix;

    public RedisStateBackend(RedissonClient redisson) {
        this(redisson, "state:");
    }

    public RedisStateBackend(RedissonClient redisson, String keyPrefix) {
        this.redisson = redisson;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public <T> ValueState<T> createValueState(StateDescriptor<T> descriptor) {
        String key = keyPrefix + descriptor.getName();
        return new RedisValueState<>(redisson, key, descriptor.getType());
    }

    @Override
    public <K, V> MapState<K, V> createMapState(String name, Class<K> keyType, Class<V> valueType) {
        String key = keyPrefix + name;
        return new RedisMapState<>(redisson, key, keyType, valueType);
    }

    @Override
    public <T> ListState<T> createListState(StateDescriptor<T> descriptor) {
        String key = keyPrefix + descriptor.getName();
        return new RedisListState<>(redisson, key, descriptor.getType());
    }

    @Override
    public <T> SetState<T> createSetState(StateDescriptor<T> descriptor) {
        String key = keyPrefix + descriptor.getName();
        return new RedisSetState<>(redisson, key, descriptor.getType());
    }

    @Override
    public void close() {
        // RedissonClient lifecycle is managed externally
    }

    public RedissonClient getRedisson() {
        return redisson;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }
}
