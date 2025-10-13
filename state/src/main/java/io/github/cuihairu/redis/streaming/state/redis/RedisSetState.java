package io.github.cuihairu.redis.streaming.state.redis;

import io.github.cuihairu.redis.streaming.api.state.SetState;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.util.HashSet;

/**
 * Redis-based implementation of SetState.
 *
 * @param <T> The type of elements
 */
public class RedisSetState<T> implements SetState<T> {

    private final RedissonClient redisson;
    private final String key;
    private final Class<T> type;

    public RedisSetState(RedissonClient redisson, String key, Class<T> type) {
        this.redisson = redisson;
        this.key = key;
        this.type = type;
    }

    private RSet<T> getSet() {
        return redisson.getSet(key);
    }

    @Override
    public boolean add(T value) {
        return getSet().add(value);
    }

    @Override
    public boolean remove(T value) {
        return getSet().remove(value);
    }

    @Override
    public boolean contains(T value) {
        return getSet().contains(value);
    }

    @Override
    public Iterable<T> get() {
        return new HashSet<>(getSet());
    }

    @Override
    public boolean isEmpty() {
        return getSet().isEmpty();
    }

    @Override
    public int size() {
        return getSet().size();
    }

    @Override
    public void clear() {
        getSet().clear();
    }

    public String getKey() {
        return key;
    }
}
