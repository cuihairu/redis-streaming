package io.github.cuihairu.redis.streaming.state.redis;

import io.github.cuihairu.redis.streaming.api.state.ListState;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis-based implementation of ListState.
 *
 * @param <T> The type of elements
 */
public class RedisListState<T> implements ListState<T> {

    private final RedissonClient redisson;
    private final String key;
    private final Class<T> type;

    public RedisListState(RedissonClient redisson, String key, Class<T> type) {
        this.redisson = redisson;
        this.key = key;
        this.type = type;
    }

    private RList<T> getList() {
        return redisson.getList(key);
    }

    @Override
    public void add(T value) {
        getList().add(value);
    }

    @Override
    public Iterable<T> get() {
        return new ArrayList<>(getList());
    }

    @Override
    public void update(Iterable<T> values) {
        RList<T> list = getList();
        list.clear();
        for (T value : values) {
            list.add(value);
        }
    }

    @Override
    public void addAll(Iterable<T> values) {
        RList<T> list = getList();
        for (T value : values) {
            list.add(value);
        }
    }

    @Override
    public void clear() {
        getList().clear();
    }

    public String getKey() {
        return key;
    }
}
