package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.state.ListState;
import io.github.cuihairu.redis.streaming.api.state.MapState;
import io.github.cuihairu.redis.streaming.api.state.SetState;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.api.RKeys;
import org.redisson.api.RSet;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.cuihairu.redis.streaming.runtime.redis.KeyedStateHotKeyException;
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
    /** per-key mutual exclusion for keyed sections (see {@link #keyFence(Object)}) */
    private final java.util.concurrent.locks.ReentrantLock[] keyFence;
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
    private final RedisRuntimeConfig.HotKeyPolicy keyedStateHotKeyPolicy;
    private final long keyedStateHotKeyThrottleMaxMs;
    private final boolean stateSchemaEvolutionEnabled;
    private final RedisRuntimeConfig.StateSchemaMismatchPolicy stateSchemaMismatchPolicy;
    private final AtomicLong stateWriteCount = new AtomicLong();
    private final ConcurrentHashMap<String, RMap<String, String>> maps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RList<String>> lists = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RSet<String>> sets = new ConcurrentHashMap<>();
    private final String stateKeyIndexKey;
    private final RSet<String> stateKeyIndex;
    private final String stateSchemaKey;
    private final RMap<String, String> stateSchema;
    private final ConcurrentHashMap<String, String> schemaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> hotKeyLastWarnAtMs = new ConcurrentHashMap<>();
    /** per redisKey: epoch ms until which hot-key handling applies to every write */
    private final ConcurrentHashMap<String, Long> hotKeyActiveUntilMs = new ConcurrentHashMap<>();

    private static final int KEY_FENCE_STRIPES = 512;

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
                               RedisRuntimeConfig.HotKeyPolicy keyedStateHotKeyPolicy,
                               long keyedStateHotKeyThrottleMaxMs,
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
        this.keyedStateHotKeyPolicy = keyedStateHotKeyPolicy == null ? RedisRuntimeConfig.HotKeyPolicy.LOG_ONLY : keyedStateHotKeyPolicy;
        this.keyedStateHotKeyThrottleMaxMs = Math.max(0, keyedStateHotKeyThrottleMaxMs);
        this.stateSchemaEvolutionEnabled = stateSchemaEvolutionEnabled;
        this.stateSchemaMismatchPolicy = stateSchemaMismatchPolicy == null
                ? RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL
                : stateSchemaMismatchPolicy;
        this.stateKeyIndexKey = stateKeyPrefix + ":" + jobName + ":stateKeys";
        this.stateKeyIndex = redissonClient.getSet(this.stateKeyIndexKey, StringCodec.INSTANCE);
        this.stateSchemaKey = stateKeyPrefix + ":" + jobName + ":stateSchema";
        this.stateSchema = redissonClient.getMap(this.stateSchemaKey, StringCodec.INSTANCE);
        this.keyFence = new java.util.concurrent.locks.ReentrantLock[KEY_FENCE_STRIPES];
        for (int i = 0; i < keyFence.length; i++) {
            keyFence[i] = new java.util.concurrent.locks.ReentrantLock();
        }
    }

    /** Package-private access for same-package state impls that need raw batch writes. */
    RedissonClient redissonClient() {
        return redissonClient;
    }

    String stateKeyIndexKey() {
        return stateKeyIndexKey;
    }

    String stateSchemaKey() {
        return stateSchemaKey;
    }

    record StateMapRef(String redisKey, RMap<String, String> map) {
    }

    /**
     * Fence a keyed section: the lock to hold while state for {@code key} is read or written.
     *
     * <p>Partitioning alone used to serialise a key's records, because one partition is read by
     * one worker. A dual-source pipeline breaks that: the same join key arrives on two topics,
     * hence two streams and two partition workers, so both sides can run the same keyed
     * read-modify-write at once and one side's write is lost (a join pair silently disappears).
     * Striping by key restores the guarantee without serialising unrelated keys, and records that
     * collide on a stripe merely queue up.
     *
     * <p>Striped, not one lock per key: the stripe count is fixed, so a stream of distinct keys
     * cannot grow the lock table.
     */
    public java.util.concurrent.locks.ReentrantLock keyFence(K key) {
        int h = key == null ? 0 : key.hashCode();
        h ^= (h >>> 16); // spread, so hashCodes that differ only in high bits share stripes evenly
        return keyFence[(h & 0x7fffffff) % keyFence.length];
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

    /**
     * Map state under the current key: one Redis Hash per (current key), fields are the
     * serialized map keys. Unlike value state (many keys share one sharded hash), the
     * per-key structure is its own fan-out unit, so no sharding applies and the key is
     * registered in the {@code stateKeys} checkpoint index directly.
     */
    public <MK, MV> MapState<MK, MV> getMapState(String name, Class<MK> keyType, Class<MV> valueType) {
        return new RedisKeyedMapState<>(this, name, keyType, valueType, objectMapper);
    }

    public <T> ListState<T> getListState(StateDescriptor<T> descriptor) {
        return new RedisKeyedListState<>(this, descriptor, objectMapper);
    }

    public <T> SetState<T> getSetState(StateDescriptor<T> descriptor) {
        return new RedisKeyedSetState<>(this, descriptor, objectMapper);
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
            } catch (Exception ex) {
                log.warn("Keyed state operation failed", ex);
            }
            return redissonClient.getMap(k, StringCodec.INSTANCE);
        });
        return new StateMapRef(redisKey, map);
    }

    RMap<String, String> stateMap(String stateName, String stateField) {
        return stateMapRef(stateName, stateField).map();
    }

    /**
     * Full redis key of a per-current-key state structure (Map/List/Set state): the keyed
     * state layout with the serialized current key appended — no sharding, because the
     * per-key structure is already the fan-out unit.
     */
    String stateItemKey(String stateName, String stateField) {
        Integer pid = currentPartitionId();
        if (pid == null) {
            throw new IllegalStateException("No current partitionId is set for keyed state access");
        }
        if (pid < 0) {
            throw new IllegalStateException("Invalid current partitionId for keyed state access: " + pid);
        }
        return stateKeyPrefix + ":" + jobName +
                ":cg:" + consumerGroup +
                ":topic:" + topic +
                ":p:" + pid +
                ":state:" + operatorId + ":" + stateName + ":" + stateField;
    }

    StateMapRef stateMapRefForKey(String stateName, String stateField) {
        String redisKey = stateItemKey(stateName, stateField);
        RMap<String, String> map = maps.computeIfAbsent(redisKey, k -> {
            registerStateKey(k);
            return redissonClient.getMap(k, StringCodec.INSTANCE);
        });
        return new StateMapRef(redisKey, map);
    }

    RList<String> stateListForKey(String stateName, String stateField) {
        String redisKey = stateItemKey(stateName, stateField);
        RList<String> list = lists.computeIfAbsent(redisKey, k -> {
            registerStateKey(k);
            return redissonClient.getList(k, StringCodec.INSTANCE);
        });
        refreshStateKeyTtl(redisKey); // sliding TTL, the list/set counterpart of touch()
        return list;
    }

    RSet<String> stateSetForKey(String stateName, String stateField) {
        String redisKey = stateItemKey(stateName, stateField);
        RSet<String> set = sets.computeIfAbsent(redisKey, k -> {
            registerStateKey(k);
            return redissonClient.getSet(k, StringCodec.INSTANCE);
        });
        refreshStateKeyTtl(redisKey);
        return set;
    }

    private void refreshStateKeyTtl(String redisKey) {
        Duration ttl = stateTtl;
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redissonClient.getKeys().expire(ttl, redisKey);
        } catch (Exception ex) {
            log.debug("Keyed state operation failed", ex);
        }
    }

    void ensureSchema(StateMapRef ref, StateDescriptor<?> descriptor) {
        if (ref == null) {
            return;
        }
        ensureSchemaForKey(ref.redisKey(), descriptor);
    }

    void ensureSchemaForKey(String redisKey, StateDescriptor<?> descriptor) {
        if (!stateSchemaEvolutionEnabled) {
            return;
        }
        if (descriptor == null) {
            return;
        }
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
        } catch (Exception ex) {
            log.debug("Keyed state operation failed", ex);
        }
        if (stored == null || stored.isBlank()) {
            try {
                stateSchema.put(redisKey, expected);
            } catch (Exception ex) {
                log.debug("Keyed state operation failed", ex);
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
                } catch (Exception ex) {
                    log.debug("Keyed state operation failed", ex);
                }
                try {
                    stateSchema.put(redisKey, expected);
                } catch (Exception ex) {
                    log.debug("Keyed state operation failed", ex);
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
            } catch (Exception ex) {
                log.debug("Keyed state operation failed", ex);
            }
        }

        // Hot-key handling: sampled detection below arms a per-key window; while active,
        // EVERY write to that key goes through the configured policy (not just samples).
        if (keyedStateHotKeyPolicy != RedisRuntimeConfig.HotKeyPolicy.LOG_ONLY && redisKey != null) {
            Long activeUntil = hotKeyActiveUntilMs.get(redisKey);
            long now = System.currentTimeMillis();
            if (activeUntil != null && now < activeUntil) {
                if (keyedStateHotKeyPolicy == RedisRuntimeConfig.HotKeyPolicy.FAIL_FAST) {
                    throw new KeyedStateHotKeyException("Keyed state hash is hot (fields >= "
                            + keyedStateHotKeyFieldsWarnThreshold + "): " + redisKey
                            + " (state=" + stateName + "); policy=FAIL_FAST");
                }
                // THROTTLE: latency as backpressure; a flat bounded sleep keeps behavior
                // deterministic and testable. Interrupts never break the pipeline.
                if (keyedStateHotKeyThrottleMaxMs > 0) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(keyedStateHotKeyThrottleMaxMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else if (activeUntil != null) {
                // expired window — remove to keep the map bounded
                hotKeyActiveUntilMs.remove(redisKey, activeUntil);
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
                // (re)arm the handling window on every hot sample so it slides forward
                // while the key stays hot — independent of the warn rate limit below
                if (redisKey != null && intervalMs > 0
                        && keyedStateHotKeyPolicy != RedisRuntimeConfig.HotKeyPolicy.LOG_ONLY) {
                    hotKeyActiveUntilMs.put(redisKey, now + intervalMs);
                }
                Long prev = redisKey == null ? null : hotKeyLastWarnAtMs.get(redisKey);
                if (prev == null || intervalMs <= 0 || now - prev >= intervalMs) {
                    if (redisKey != null) {
                        hotKeyLastWarnAtMs.put(redisKey, now);
                    }
                    try {
                        RedisRuntimeMetrics.get().incKeyedStateHotKey(jobName, topic, consumerGroup, operatorId, stateName, pid, size);
                    } catch (Exception ex) {
                        log.debug("Keyed state operation failed", ex);
                    }
                    log.warn("Redis runtime hot keyed-state detected (jobName={}, topic={}, group={}, operator={}, state={}, partition={}, fields={}, redisKey={})",
                            jobName, topic, consumerGroup, operatorId, stateName, pid, size, redisKey);
                }
            }
        } catch (Exception ex) {
            log.debug("Keyed state operation failed", ex);
        }
    }

    /**
     * Register a non-map Redis state key for checkpoint snapshot/restore.
     *
     * <p>Used for auxiliary runtime state (e.g., window indexes) stored as non-hash Redis types.</p>
     */
    void registerStateKey(String redisKey) {
        if (redisKey == null || redisKey.isBlank()) {
            return;
        }
        try {
            stateKeyIndex.add(redisKey);
        } catch (Exception ex) {
            log.warn("Keyed state operation failed", ex);
        }
        refreshStateKeyTtl(redisKey);
    }

    void recordKeyedStateRead(String stateName, long millis) {
        Integer pid = currentPartitionId();
        if (pid == null || pid < 0) {
            return;
        }
        try {
            RedisRuntimeMetrics.get().incKeyedStateRead(jobName, topic, consumerGroup, operatorId, stateName, pid);
        } catch (Exception ex) {
            log.debug("Keyed state operation failed", ex);
        }
        if (millis >= 0) {
            try {
                RedisRuntimeMetrics.get().recordKeyedStateReadLatency(jobName, topic, consumerGroup, operatorId, stateName, pid, millis);
            } catch (Exception ex) {
                log.debug("Keyed state operation failed", ex);
            }
        }
    }

    void recordKeyedStateWrite(String stateName, long millis) {
        Integer pid = currentPartitionId();
        if (pid == null || pid < 0) {
            return;
        }
        try {
            RedisRuntimeMetrics.get().incKeyedStateWrite(jobName, topic, consumerGroup, operatorId, stateName, pid);
        } catch (Exception ex) {
            log.debug("Keyed state operation failed", ex);
        }
        if (millis >= 0) {
            try {
                RedisRuntimeMetrics.get().recordKeyedStateWriteLatency(jobName, topic, consumerGroup, operatorId, stateName, pid, millis);
            } catch (Exception ex) {
                log.debug("Keyed state operation failed", ex);
            }
        }
    }

    void recordKeyedStateDelete(String stateName) {
        Integer pid = currentPartitionId();
        if (pid == null || pid < 0) {
            return;
        }
        try {
            RedisRuntimeMetrics.get().incKeyedStateDelete(jobName, topic, consumerGroup, operatorId, stateName, pid);
        } catch (Exception ex) {
            log.debug("Keyed state operation failed", ex);
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
