package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remaining branch coverage for {@link RedisKeyedStateStore} and {@link RedisKeyedValueState}:
 * stateMap access, ensureSchema edge inputs and IGNORE policy, touch TTL/report/hot-key
 * branches, registerStateKey shapes, the recordKeyedState* metric hooks and the value state
 * serialize/deserialize/delete paths.
 */
@Tag("integration")
class RedisKeyedStateStoreDeepIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private static RedisKeyedStateStore<String> store(RedissonClient redis, String prefix,
                                                       Duration ttl, int sizeReportEvery,
                                                       long hotKeyThreshold, Duration hotKeyInterval,
                                                       boolean evolution,
                                                       RedisRuntimeConfig.StateSchemaMismatchPolicy policy) {
        return new RedisKeyedStateStore<>(redis, new com.fasterxml.jackson.databind.ObjectMapper(),
                prefix, "job", "t", "g", "op", ttl, sizeReportEvery, 1,
                hotKeyThreshold, hotKeyInterval, evolution, policy);
    }

    @Test
    void stateMapMatchesStateMapRef() {
        RedissonClient redis = client();
        String prefix = "it-rti-ss:" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKeyedStateStore<String> s = store(redis, prefix, Duration.ZERO, 0, 0L,
                    Duration.ofMinutes(1), false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            s.setCurrentPartitionId(0);
            RMap<String, String> direct = s.stateMap("st", "f");
            assertNotNull(direct);
            assertSame(direct, s.stateMapRef("st", "f").map(), "cached map instance must be reused");
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void ensureSchemaEdgeInputsAndIgnorePolicy() {
        RedissonClient redis = client();
        String prefix = "it-rti-ss:" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKeyedStateStore<String> s = store(redis, prefix, Duration.ZERO, 0, 0L,
                    Duration.ofMinutes(1), true, RedisRuntimeConfig.StateSchemaMismatchPolicy.IGNORE);
            s.setCurrentPartitionId(0);

            RedisKeyedStateStore.StateMapRef ref = s.stateMapRef("st", "f1");
            s.ensureSchema(null, new StateDescriptor<>("st", String.class));
            s.ensureSchema(ref, null);
            s.ensureSchema(new RedisKeyedStateStore.StateMapRef(" ", ref.map()),
                    new StateDescriptor<>("st", String.class));

            // first registration stores schema; second is a cache hit
            StateDescriptor<String> v1 = new StateDescriptor<>("st", String.class, null, 1);
            s.ensureSchema(ref, v1);
            s.ensureSchema(ref, v1);
            assertEquals(String.class.getName() + "|1",
                    redis.getMap(s.stateSchemaKey(), StringCodec.INSTANCE).get(ref.redisKey()));

            // mismatch under IGNORE proceeds and refreshes the cache
            s.ensureSchema(ref, new StateDescriptor<>("st", Long.class, null, 3));
            s.ensureSchema(ref, new StateDescriptor<>("st", Long.class, null, 3));
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void touchTtlReportAndHotKeyBranches() {
        RedissonClient redis = client();
        String prefix = "it-rti-ss:" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // ttl applied + report every 2 writes + hot-key threshold with long interval
            RedisKeyedStateStore<String> s = store(redis, prefix, Duration.ofSeconds(60), 2, 1L,
                    Duration.ofHours(1), false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            s.setCurrentPartitionId(0);
            RedisKeyedStateStore.StateMapRef ref = s.stateMapRef("st", "f1");
            ref.map().put("f1", "v1");
            s.touch(ref.redisKey(), "st", ref.map()); // write 1: no report
            s.touch(ref.redisKey(), "st", ref.map()); // write 2: report + hot-key warn
            s.touch(ref.redisKey(), "st", ref.map()); // write 3: hot-key warn suppressed by interval
            long ttl = redis.getMap(ref.redisKey(), StringCodec.INSTANCE).remainTimeToLive();
            assertTrue(ttl > 0, "touch must apply TTL, was " + ttl);

            s.touch(ref.redisKey(), "st", null); // null map no-op

            // report path without partition binding returns early
            s.clearCurrentPartitionId();
            s.touch(ref.redisKey(), "st", ref.map());
            s.setCurrentPartitionId(0);

            // zero ttl store skips expire entirely
            RedisKeyedStateStore<String> zeroTtl = store(redis, prefix + ":z", Duration.ZERO, 1, 0L,
                    Duration.ofMinutes(1), false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            zeroTtl.setCurrentPartitionId(0);
            RedisKeyedStateStore.StateMapRef zref = zeroTtl.stateMapRef("st", "f1");
            zref.map().put("f1", "v");
            zeroTtl.touch(zref.redisKey(), "st", zref.map());
            assertEquals(-1L, redis.getMap(zref.redisKey(), StringCodec.INSTANCE).remainTimeToLive());
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void registerStateKeyShapes() {
        RedissonClient redis = client();
        String prefix = "it-rti-ss:" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKeyedStateStore<String> s = store(redis, prefix, Duration.ofSeconds(30), 0, 0L,
                    Duration.ofMinutes(1), false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            s.registerStateKey(null);
            s.registerStateKey(" ");
            String key = prefix + ":aux";
            redis.getScoredSortedSet(key, StringCodec.INSTANCE).add(1D, "m");
            s.registerStateKey(key);
            long ttl = redis.getKeys().remainTimeToLive(key);
            assertTrue(ttl > 0, "registerStateKey must apply TTL via key expire, was " + ttl);

            RedisKeyedStateStore<String> zeroTtl = store(redis, prefix + ":z", Duration.ZERO, 0, 0L,
                    Duration.ofMinutes(1), false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            String key2 = prefix + ":z:aux";
            redis.getScoredSortedSet(key2, StringCodec.INSTANCE).add(1D, "m");
            zeroTtl.registerStateKey(key2);
            assertEquals(-1L, redis.getKeys().remainTimeToLive(key2));
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void recordMetricHooksCoverBothBindingStates() {
        RedissonClient redis = client();
        String prefix = "it-rti-ss:" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKeyedStateStore<String> s = store(redis, prefix, Duration.ZERO, 0, 0L,
                    Duration.ofMinutes(1), false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            // no partition -> early return
            s.recordKeyedStateRead("st", 5L);
            s.recordKeyedStateRead("st", -1L);
            s.recordKeyedStateWrite("st", 5L);
            s.recordKeyedStateDelete("st");

            // partition bound -> metric calls, with and without latency samples
            s.setCurrentPartitionId(2);
            s.recordKeyedStateRead("st", 5L);
            s.recordKeyedStateRead("st", -1L);
            s.recordKeyedStateWrite("st", 0L);
            s.recordKeyedStateWrite("st", -5L);
            s.recordKeyedStateDelete("st");
            s.clearCurrentPartitionId();
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void valueStateSerializeDeserializeAndDeletePaths() {
        RedissonClient redis = client();
        String prefix = "it-rti-ss:" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKeyedStateStore<String> s = store(redis, prefix, Duration.ZERO, 0, 0L,
                    Duration.ofMinutes(1), false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            s.setCurrentPartitionId(0);
            s.setCurrentKey("k");

            ValueState<String> state = s.getValueState(new StateDescriptor<>("s", String.class, "dflt"));
            state.update("v");
            assertEquals("v", state.value());
            state.update(null); // delete path
            assertEquals("dflt", state.value());
            state.update("v2");
            state.clear();
            assertEquals("dflt", state.value());

            // corrupt stored json -> deserialize failure
            RMap<String, String> map = s.stateMap("s", "s:k");
            map.put("s:k", "not-json");
            RuntimeException e1 = assertThrows(RuntimeException.class, state::value);
            assertTrue(e1.getMessage().contains("Failed to deserialize state value for"), "" + e1);

            // unserializable value -> serialize failure
            ValueState<Evil> evilState = s.getValueState(new StateDescriptor<>("evil", Evil.class));
            RuntimeException e2 = assertThrows(RuntimeException.class, () -> evilState.update(new Evil()));
            assertTrue(e2.getMessage().contains("Failed to serialize state value for"), "" + e2);

            s.clearCurrentKey();
            assertThrows(IllegalStateException.class, state::value);
            assertNull(s.currentKey());
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    public static final class Evil {
        public String getBoom() {
            throw new IllegalStateException("no-serialization");
        }
    }
}
