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

/** Drives touch()/size-report/hot-key/TTL paths of RedisKeyedStateStore on real Redis. */
@Tag("integration")
class StateStoreTouchReportIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void reportIntervalHotKeyAndTtlPaths() throws Exception {
        RedissonClient redis = client();
        String prefix = "st-report-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            RedisKeyedStateStore<String> store = new RedisKeyedStateStore<>(redis,
                    new com.fasterxml.jackson.databind.ObjectMapper(), prefix, "job", "t", "g", "op",
                    Duration.ofSeconds(60), 2, 1, 1, Duration.ofMillis(1),
                    true, RedisRuntimeConfig.StateSchemaMismatchPolicy.IGNORE);
            store.setCurrentPartitionId(0);
            ValueState<String> state = store.getValueState(new StateDescriptor<>("counter", String.class, "0"));
            store.setCurrentKey("k1");
            for (int i = 0; i < 5; i++) {
                state.update("v" + i); // write + read cycles through touch with report every 2 writes
            }
            assertEquals("v4", state.value());
            // hot-key path: threshold=1 with the 5 stored fields triggers the warn branch
            RedisKeyedStateStore.StateMapRef ref = store.stateMapRef("counter", store.stateFieldForKey("k1"));
            for (int i = 0; i < 5; i++) {
                ref.map().put("f" + i, "x");
            }
            store.touch(ref.redisKey(), "counter", ref.map());
            assertTrue(ref.map().size() >= 6);
            long ttl = redis.getMap(ref.redisKey(), org.redisson.client.codec.StringCodec.INSTANCE).remainTimeToLive();
            store.clearCurrentKey();
            store.clearCurrentPartitionId();
            assertTrue(ttl > 0, "TTL should be applied by touch, was " + ttl);
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void zsetAndSchemaSnapshotRestoreRoundTrip() throws Exception {
        RedissonClient redis = client();
        String prefix = "st-zset-" + UUID.randomUUID().toString().substring(0, 8);
        String job = "zjob-" + UUID.randomUUID().toString().substring(0, 6);
        try {
            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder().jobName(job).stateKeyPrefix(prefix).build();
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(redis, cfg);
            RedisKeyedStateStore<String> store = new RedisKeyedStateStore<>(redis,
                    new com.fasterxml.jackson.databind.ObjectMapper(), prefix, job, "t", "g", "op",
                    Duration.ZERO, 0, 1, 0L, Duration.ofMinutes(1), true,
                    RedisRuntimeConfig.StateSchemaMismatchPolicy.IGNORE);
            store.setCurrentPartitionId(0);
            store.setCurrentKey("k");
            RedisKeyedStateStore.StateMapRef ref = store.stateMapRef("plain", store.stateFieldForKey("k"));
            ref.map().put("s:k", "hello");
            store.registerStateKey(ref.redisKey());
            store.touch(ref.redisKey(), "plain", ref.map());

            String dueKey = prefix + ":" + job + ":cg:g:topic:t:p:0:windowDue:op:z";
            redis.getScoredSortedSet(dueKey, org.redisson.client.codec.StringCodec.INSTANCE).add(9.9, "m1");
            store.registerStateKey(dueKey);

            io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint cp =
                    mgr.triggerCheckpoint(java.util.List.of(new RedisRuntimeCheckpointManager.PipelineKey("t", "g")));
            assertNotNull(cp);

            ref.map().delete();
            redis.getScoredSortedSet(dueKey, org.redisson.client.codec.StringCodec.INSTANCE).delete();
            assertTrue(mgr.restoreFromCheckpoint(cp, java.util.List.of(new RedisRuntimeCheckpointManager.PipelineKey("t", "g"))));
            assertEquals("hello", store.stateMapRef("plain", store.stateFieldForKey("k")).map().get("s:k"));
            assertEquals(1, redis.getScoredSortedSet(dueKey, org.redisson.client.codec.StringCodec.INSTANCE).size());
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }
}
