package io.github.cuihairu.redis.streaming.state.redis;

import io.github.cuihairu.redis.streaming.api.state.ValueState;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

/**
 * Redis-based implementation of ValueState.
 *
 * @param <T> The type of the value
 */
public class RedisValueState<T> implements ValueState<T> {

    private final RedissonClient redisson;
    private final String key;
    private final Class<T> type;

    public RedisValueState(RedissonClient redisson, String key, Class<T> type) {
        this.redisson = redisson;
        this.key = key;
        this.type = type;
    }

    @Override
    public T value() {
        RBucket<T> bucket = redisson.getBucket(key);
        return bucket.get();
    }

    @Override
    public void update(T value) {
        RBucket<T> bucket = redisson.getBucket(key);
        bucket.set(value);
    }

    @Override
    public void clear() {
        RBucket<T> bucket = redisson.getBucket(key);
        bucket.delete();
    }

    public String getKey() {
        return key;
    }
}
