package io.github.cuihairu.redis.streaming.state.redis;

import io.github.cuihairu.redis.streaming.api.state.ListState;
import org.redisson.api.BatchOptions;
import org.redisson.api.RBatch;
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
        // MULTI/EXEC keeps the replacement atomic: the old clear()+add loop destroyed
        // the old state up front, so a connection dropped mid-way left the key emptied
        // or half-written (B-38). Readers now see either the old list or the new one.
        List<T> buffered = new ArrayList<>();
        values.forEach(buffered::add);

        RBatch batch = redisson.createBatch(
                BatchOptions.defaults().executionMode(BatchOptions.ExecutionMode.REDIS_WRITE_ATOMIC));
        batch.getBucket(key).deleteAsync();
        if (!buffered.isEmpty()) {
            batch.getList(key).addAllAsync(buffered);
        }
        batch.execute();
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
