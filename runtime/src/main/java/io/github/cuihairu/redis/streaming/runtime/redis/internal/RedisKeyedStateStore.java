package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed keyed state store used by the Redis runtime.
 *
 * <p>State is stored as a Redis Hash per (job, operator, stateName) and fields are the serialized key.</p>
 */
public final class RedisKeyedStateStore<K> {

    private final ThreadLocal<K> currentKey = new ThreadLocal<>();
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String stateKeyPrefix;
    private final String jobName;
    private final String operatorId;
    private final ConcurrentHashMap<String, RMap<String, String>> maps = new ConcurrentHashMap<>();

    public RedisKeyedStateStore(RedissonClient redissonClient,
                               ObjectMapper objectMapper,
                               String stateKeyPrefix,
                               String jobName,
                               String operatorId) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.stateKeyPrefix = Objects.requireNonNull(stateKeyPrefix, "stateKeyPrefix");
        this.jobName = Objects.requireNonNull(jobName, "jobName");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
    }

    public void setCurrentKey(K key) {
        currentKey.set(key);
    }

    public K currentKey() {
        return currentKey.get();
    }

    public void clearCurrentKey() {
        currentKey.remove();
    }

    public <T> ValueState<T> getValueState(StateDescriptor<T> descriptor) {
        return new RedisKeyedValueState<>(this, descriptor, objectMapper);
    }

    RMap<String, String> stateMap(String stateName) {
        String redisKey = stateKeyPrefix + ":" + jobName + ":state:" + operatorId + ":" + stateName;
        return maps.computeIfAbsent(redisKey, k -> redissonClient.getMap(k, StringCodec.INSTANCE));
    }
}

