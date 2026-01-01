package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.api.RSet;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;

/**
 * Redis-backed keyed state store used by the Redis runtime.
 *
 * <p>State is stored as a Redis Hash per (job, operator, stateName) and fields are the serialized key.</p>
 */
public final class RedisKeyedStateStore<K> {

    private static final Logger log = LoggerFactory.getLogger(RedisKeyedStateStore.class);

    private final ThreadLocal<K> currentKey = new ThreadLocal<>();
    private final ThreadLocal<Integer> currentPartitionId = new ThreadLocal<>();
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final String stateKeyPrefix;
    private final String jobName;
    private final String topic;
    private final String consumerGroup;
    private final String operatorId;
    private final Duration stateTtl;
    private final int stateSizeReportEveryNStateWrites;
    private final int keyedStateShardCount;
    private final long keyedStateHotKeyFieldsWarnThreshold;
    private final Duration keyedStateHotKeyWarnInterval;
    private final boolean stateSchemaEvolutionEnabled;
    private final RedisRuntimeConfig.StateSchemaMismatchPolicy stateSchemaMismatchPolicy;
    private final AtomicLong stateWriteCount = new AtomicLong();
    private final ConcurrentHashMap<String, RMap<String, String>> maps = new ConcurrentHashMap<>();
    private final String stateKeyIndexKey;
    private final RSet<String> stateKeyIndex;
    private final String stateSchemaKey;
    private final RMap<String, String> stateSchema;
    private final ConcurrentHashMap<String, String> schemaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> hotKeyLastWarnAtMs = new ConcurrentHashMap<>();

    public RedisKeyedStateStore(RedissonClient redissonClient,
                               ObjectMapper objectMapper,
                               String stateKeyPrefix,
                               String jobName,
                               String topic,
                               String consumerGroup,
                               String operatorId,
                               Duration stateTtl,
                               int stateSizeReportEveryNStateWrites,
                               int keyedStateShardCount,
                               long keyedStateHotKeyFieldsWarnThreshold,
                               Duration keyedStateHotKeyWarnInterval,
                               boolean stateSchemaEvolutionEnabled,
                               RedisRuntimeConfig.StateSchemaMismatchPolicy stateSchemaMismatchPolicy) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.stateKeyPrefix = Objects.requireNonNull(stateKeyPrefix, "stateKeyPrefix");
        this.jobName = Objects.requireNonNull(jobName, "jobName");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.consumerGroup = Objects.requireNonNull(consumerGroup, "consumerGroup");
        this.operatorId = Objects.requireNonNull(operatorId, "operatorId");
        this.stateTtl = stateTtl == null ? Duration.ZERO : stateTtl;
        this.stateSizeReportEveryNStateWrites = Math.max(0, stateSizeReportEveryNStateWrites);
        this.keyedStateShardCount = Math.max(1, keyedStateShardCount);
        this.keyedStateHotKeyFieldsWarnThreshold = Math.max(0, keyedStateHotKeyFieldsWarnThreshold);
        this.keyedStateHotKeyWarnInterval = keyedStateHotKeyWarnInterval == null ? Duration.ofMinutes(1) : keyedStateHotKeyWarnInterval;
        this.stateSchemaEvolutionEnabled = stateSchemaEvolutionEnabled;
        this.stateSchemaMismatchPolicy = stateSchemaMismatchPolicy == null
                ? RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL
                : stateSchemaMismatchPolicy;
        this.stateKeyIndexKey = stateKeyPrefix + ":" + jobName + ":stateKeys";
        this.stateKeyIndex = redissonClient.getSet(this.stateKeyIndexKey, StringCodec.INSTANCE);
        this.stateSchemaKey = stateKeyPrefix + ":" + jobName + ":stateSchema";
        this.stateSchema = redissonClient.getMap(this.stateSchemaKey, StringCodec.INSTANCE);
    }

    String stateKeyIndexKey() {
        return stateKeyIndexKey;
    }

    String stateSchemaKey() {
        return stateSchemaKey;
    }

    record StateMapRef(String redisKey, RMap<String, String> map) {
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

    public void setCurrentPartitionId(int partitionId) {
        currentPartitionId.set(partitionId);
    }

    public Integer currentPartitionId() {
        return currentPartitionId.get();
    }

    public void clearCurrentPartitionId() {
        currentPartitionId.remove();
    }

    public <T> ValueState<T> getValueState(StateDescriptor<T> descriptor) {
        return new RedisKeyedValueState<>(this, descriptor, objectMapper);
    }

    StateMapRef stateMapRef(String stateName, String stateField) {
        Integer pid = currentPartitionId();
        if (pid == null) {
            throw new IllegalStateException("No current partitionId is set for keyed state access");
        }
        if (pid < 0) {
            throw new IllegalStateException("Invalid current partitionId for keyed state access: " + pid);
        }
        String redisKey = stateKeyPrefix + ":" + jobName +
                ":cg:" + consumerGroup +
                ":topic:" + topic +
                ":p:" + pid +
                ":state:" + operatorId + ":" + stateName;
        int shards = keyedStateShardCount;
        if (shards > 1) {
            int shard = Math.floorMod(stateField == null ? 0 : stateField.hashCode(), shards);
            redisKey = redisKey + ":shard:" + shard;
        }
        RMap<String, String> map = maps.computeIfAbsent(redisKey, k -> {
            try {
                stateKeyIndex.add(k);
            } catch (Exception ignore) {
            }
            return redissonClient.getMap(k, StringCodec.INSTANCE);
        });
        return new StateMapRef(redisKey, map);
    }

    RMap<String, String> stateMap(String stateName, String stateField) {
        return stateMapRef(stateName, stateField).map();
    }

    void ensureSchema(StateMapRef ref, StateDescriptor<?> descriptor) {
        if (!stateSchemaEvolutionEnabled) {
            return;
        }
        if (ref == null || descriptor == null) {
            return;
        }
        String redisKey = ref.redisKey();
        if (redisKey == null || redisKey.isBlank()) {
            return;
        }

        String expected = descriptor.getType().getName() + "|" + descriptor.getSchemaVersion();
        String cached = schemaCache.get(redisKey);
        if (expected.equals(cached)) {
            return;
        }

        String stored = null;
        try {
            stored = stateSchema.get(redisKey);
        } catch (Exception ignore) {
        }
        if (stored == null || stored.isBlank()) {
            try {
                stateSchema.put(redisKey, expected);
            } catch (Exception ignore) {
            }
            schemaCache.put(redisKey, expected);
            return;
        }
        if (expected.equals(stored)) {
            schemaCache.put(redisKey, expected);
            return;
        }

        switch (stateSchemaMismatchPolicy) {
            case IGNORE: {
                // Intentionally ignore mismatch and proceed; cache expected to avoid repeated Redis reads.
                schemaCache.put(redisKey, expected);
                return;
            }
            case CLEAR: {
                try {
                    redissonClient.getKeys().delete(redisKey);
                } catch (Exception ignore) {
                }
                try {
                    stateSchema.put(redisKey, expected);
                } catch (Exception ignore) {
                }
                schemaCache.put(redisKey, expected);
                break;
            }
            case FAIL:
            default:
                throw new IllegalStateException("State schema mismatch for " + redisKey +
                        " (expected " + expected + ", stored " + stored + ")");
        }
    }

    void touch(String redisKey, String stateName, RMap<String, String> map) {
        if (map == null) {
            return;
        }
        Duration ttl = stateTtl;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            // continue to size reporting
        } else {
            try {
                map.expire(ttl);
            } catch (Exception ignore) {
            }
        }

        int everyN = stateSizeReportEveryNStateWrites;
        if (everyN <= 0) {
            return;
        }
        long n = stateWriteCount.incrementAndGet();
        if (n % everyN != 0) {
            return;
        }
        Integer pid = currentPartitionId();
        if (pid == null || pid < 0) {
            return;
        }
        try {
            int size = map.size();
            RedisRuntimeMetrics.get().recordKeyedStateSize(jobName, topic, consumerGroup, operatorId, stateName, pid, size);
            long threshold = keyedStateHotKeyFieldsWarnThreshold;
            if (threshold > 0 && size >= threshold) {
                long now = System.currentTimeMillis();
                long intervalMs = Math.max(0, keyedStateHotKeyWarnInterval == null ? 0 : keyedStateHotKeyWarnInterval.toMillis());
                Long prev = redisKey == null ? null : hotKeyLastWarnAtMs.get(redisKey);
                if (prev == null || intervalMs <= 0 || now - prev >= intervalMs) {
                    if (redisKey != null) {
                        hotKeyLastWarnAtMs.put(redisKey, now);
                    }
                    try {
                        RedisRuntimeMetrics.get().incKeyedStateHotKey(jobName, topic, consumerGroup, operatorId, stateName, pid, size);
                    } catch (Exception ignore) {
                    }
                    log.warn("Redis runtime hot keyed-state detected (jobName={}, topic={}, group={}, operator={}, state={}, partition={}, fields={}, redisKey={})",
                            jobName, topic, consumerGroup, operatorId, stateName, pid, size, redisKey);
                }
            }
        } catch (Exception ignore) {
        }
    }

    String stateFieldForKey(K key) {
        try {
            if (key instanceof String s) {
                return "s:" + s;
            }
            if (key instanceof Number n) {
                return "n:" + n;
            }
            return "j:" + objectMapper.writeValueAsString(key);
        } catch (Exception e) {
            return "t:" + String.valueOf(key);
        }
    }
}
