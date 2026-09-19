package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct integration coverage for {@link RedisKeyedStateStore} and
 * {@link RedisKeyedValueState}: key encoding, sharding, TTL, schema evolution policies,
 * metric/report hooks and error paths.
 */
@Tag("integration")
class RedisKeyedStateStoreIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static RedisKeyedStateStore<Object> store(RedissonClient client, String prefix, String job,
                                                       Duration ttl, int shards, int sizeReportEvery,
                                                       boolean evolution, RedisRuntimeConfig.StateSchemaMismatchPolicy policy) {
        return new RedisKeyedStateStore<>(client, new com.fasterxml.jackson.databind.ObjectMapper(),
                prefix, job, "t1", "g1", "op1", ttl, sizeReportEvery, shards, 0L, Duration.ofMinutes(1),
                evolution, policy);
    }

    @Test
    void keyEncodingAndAccessErrors() {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKeyedStateStore<Object> s = store(client, "p" + uid, "j" + uid, Duration.ZERO, 1, 0, false,
                    RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            assertEquals("s:abc", s.stateFieldForKey((Object) "abc"));
            assertEquals("n:42", s.stateFieldForKey((Object) 42L));
            assertEquals("j:", s.stateFieldForKey((Object) java.util.Map.of("a", 1)).substring(0, 2));
            assertTrue(s.stateFieldForKey((Object) new Object()).startsWith("t:"));

            // no partition bound -> illegal state
            assertThrows(IllegalStateException.class, () -> s.stateMapRef("st", "f"));
            s.setCurrentPartitionId(-5);
            assertThrows(IllegalStateException.class, () -> s.stateMapRef("st", "f"));
            s.clearCurrentPartitionId();
            assertNull(s.currentPartitionId());

            s.setCurrentPartitionId(0);
            s.setCurrentKey("k");
            assertEquals("k", s.currentKey());
            s.clearCurrentKey();
            assertNull(s.currentKey());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void valueStateRoundTripWithTtlAndIndex() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "vs-" + uid;
        try {
            RedisKeyedStateStore<Object> s = store(client, prefix, "j" + uid, Duration.ofSeconds(30), 1, 5, false,
                    RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            s.setCurrentPartitionId(0);
            ValueState<String> state = s.getValueState(new StateDescriptor<>("counter", String.class, "0"));

            s.setCurrentKey("a");
            assertEquals("0", state.value());
            state.update("7");
            assertEquals("7", state.value());

            s.setCurrentKey("b");
            assertEquals("0", state.value());
            state.update("9");
            state.clear();
            assertEquals("0", state.value());

            String redisKey = s.stateMapRef("counter", "s:a").redisKey();
            assertTrue(client.<String>getSet(s.stateKeyIndexKey(), org.redisson.client.codec.StringCodec.INSTANCE)
                    .contains(redisKey), "state key must be registered in index");
            long ttl = client.getMap(redisKey).remainTimeToLive();
            assertTrue(ttl > 0 && ttl <= 30_000L, "TTL should be applied, was " + ttl);
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void shardingSplitsStateAcrossRedisKeys() {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKeyedStateStore<Object> s = store(client, "sh" + uid, "j" + uid, Duration.ZERO, 4, 0, false,
                    RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            s.setCurrentPartitionId(0);
            java.util.Set<String> redisKeys = new java.util.HashSet<>();
            for (int i = 0; i < 40; i++) {
                redisKeys.add(s.stateMapRef("st", "field-" + i).redisKey());
            }
            assertTrue(redisKeys.size() > 1, "sharded store must spread fields across multiple keys: " + redisKeys.size());
        } finally {
            client.getKeys().deleteByPattern("sh" + uid + "*");
            client.shutdown();
        }
    }

    @Test
    void schemaEvolutionPolicies() {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "sc" + uid;
        try {
            RedisKeyedStateStore<Object> evolution = store(client, prefix, "j1" + uid, Duration.ZERO, 1, 0, true,
                    RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            evolution.setCurrentPartitionId(0);
            RedisKeyedStateStore.StateMapRef ref = evolution.stateMapRef("s", "f1");
            // first registration stores schema
            evolution.ensureSchema(ref, new StateDescriptor<>("s", String.class, null, 1));
            assertEquals(String.class.getName() + "|1",
                    client.getMap(evolution.stateSchemaKey(), org.redisson.client.codec.StringCodec.INSTANCE).get(ref.redisKey()));
            // cached expectation short-circuits
            evolution.ensureSchema(ref, new StateDescriptor<>("s", String.class, null, 1));
            // mismatch under FAIL throws
            assertThrows(IllegalStateException.class,
                    () -> evolution.ensureSchema(ref, new StateDescriptor<>("s", Long.class, null, 2)));
            // no-op when evolution disabled
            RedisKeyedStateStore<Object> off = store(client, prefix, "j2" + uid, Duration.ZERO, 1, 0, false,
                    RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
            off.setCurrentPartitionId(0);
            assertDoesNotThrow(() -> off.ensureSchema(off.stateMapRef("s", "x"),
                    new StateDescriptor<>("s", String.class)));
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void schemaClearPolicyDeletesState() {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "scc" + uid;
        try {
            RedisKeyedStateStore<Object> s = store(client, prefix, "j" + uid, Duration.ZERO, 1, 0, true,
                    RedisRuntimeConfig.StateSchemaMismatchPolicy.CLEAR);
            s.setCurrentPartitionId(0);
            RedisKeyedStateStore.StateMapRef ref = s.stateMapRef("s", "f1");
            ref.map().put("f1", "old");
            s.ensureSchema(ref, new StateDescriptor<>("s", String.class, null, 1));
            // bump version -> CLEAR drops state and stores new schema
            s.ensureSchema(ref, new StateDescriptor<>("s", String.class, null, 2));
            assertFalse(ref.map().containsKey("f1"), "CLEAR policy should have dropped the field");
            assertEquals(String.class.getName() + "|2",
                    client.getMap(s.stateSchemaKey(), org.redisson.client.codec.StringCodec.INSTANCE).get(ref.redisKey()));
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }
}
