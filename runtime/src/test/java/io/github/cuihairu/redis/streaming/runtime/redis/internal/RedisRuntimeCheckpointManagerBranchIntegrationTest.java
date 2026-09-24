package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remaining branch coverage for {@link RedisRuntimeCheckpointManager}: initNextId continuation,
 * markSinkCommitted meta creation / meta-only committed lookup, defer-ack restore selection,
 * restoreFromCheckpoint mismatch tolerance and the snapshot/restore state-shape edge cases.
 */
@Tag("integration")
class RedisRuntimeCheckpointManagerBranchIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    private static RedisRuntimeConfig cfg(String job, String prefix) {
        return RedisRuntimeConfig.builder()
                .jobName(job)
                .stateKeyPrefix(prefix)
                .checkpointKeyPrefix(prefix + ":cp")
                .build();
    }

    private static RedisRuntimeConfig deferCfg(String job, String prefix) {
        return RedisRuntimeConfig.builder()
                .jobName(job)
                .stateKeyPrefix(prefix)
                .checkpointKeyPrefix(prefix + ":cp")
                .deferAckUntilCheckpoint(true)
                .build();
    }

    @Test
    void initNextIdContinuesFromLatestStoredCheckpoint() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "it-rti-cpm:" + uid;
        try {
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(
                    redis, cfg("it-rti-cpm-" + uid, prefix));
            Checkpoint first = mgr.triggerCheckpoint(List.of());
            assertNotNull(first);
            long firstId = first.getCheckpointId();

            RedisRuntimeCheckpointManager next = new RedisRuntimeCheckpointManager(
                    redis, cfg("it-rti-cpm-" + uid, prefix));
            assertEquals(firstId + 1, next.allocateCheckpointId());
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void markSinkCommittedCreatesMetaAndMetaOnlyLookupWorks() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "it-rti-cpm:" + uid;
        try {
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(
                    redis, cfg("it-rti-cpm-" + uid, prefix));
            DefaultCheckpoint cp = new DefaultCheckpoint(1L, System.currentTimeMillis());
            assertTrue(mgr.markSinkCommitted(cp), "fresh checkpoint without meta must still commit");

            @SuppressWarnings("unchecked")
            Map<String, Object> meta = cp.getStateSnapshot().getState("runtime:meta");
            assertNotNull(meta, "markSinkCommitted must create meta when missing");
            assertEquals(Boolean.TRUE, meta.get("sinkCommitted"));

            // drop the marker: lookup must fall back to the meta sinkCommitted flag
            redis.getBucket(mgr.sinkCommittedMarkerKey(cp.getCheckpointId()), StringCodec.INSTANCE).delete();
            assertFalse(mgr.isSinkCommittedMarkerPresent(cp.getCheckpointId()));
            Checkpoint viaMeta = mgr.getLatestSinkCommittedCheckpoint();
            assertNotNull(viaMeta);
            assertEquals(cp.getCheckpointId(), viaMeta.getCheckpointId());
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void getLatestSinkCommittedCheckpointSkipsMetalessCheckpoints() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "it-rti-cpm:" + uid;
        try {
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(
                    redis, cfg("it-rti-cpm-" + uid, prefix));
            // raw checkpoint without runtime:meta must be skipped
            RedisCheckpointStorage storage = new RedisCheckpointStorage(redis, prefix + ":cp" + "it-rti-cpm-" + uid + ":");
            DefaultCheckpoint metaless = new DefaultCheckpoint(42L, System.currentTimeMillis());
            metaless.markCompleted();
            storage.storeCheckpoint(metaless);
            assertNull(mgr.getLatestSinkCommittedCheckpoint());
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void restoreFromLatestHonorsDeferAckSelection() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "it-rti-cpm:" + uid;
        String job = "it-rti-cpm-" + uid;
        try {
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(redis, cfg(job, prefix));
            Checkpoint older = mgr.triggerCheckpoint(List.of());
            Checkpoint newer = mgr.triggerCheckpoint(List.of());
            assertNotNull(older);
            assertNotNull(newer);
            assertTrue(mgr.markSinkCommitted(older));
            RedisRuntimeCheckpointManager deferMgr = new RedisRuntimeCheckpointManager(redis, deferCfg(job, prefix));

            Checkpoint normalPick = mgr.restoreFromLatestCheckpointOrNull(List.of());
            assertNotNull(normalPick);
            assertEquals(newer.getCheckpointId(), normalPick.getCheckpointId(), "plain restore picks the newest");

            Checkpoint deferPick = deferMgr.restoreFromLatestCheckpointOrNull(List.of());
            assertNotNull(deferPick);
            assertEquals(older.getCheckpointId(), deferPick.getCheckpointId(),
                    "defer-ack restore must pick the sink-committed checkpoint");
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }

    @Test
    void restoreFromCheckpointToleratesMismatchAndWeirdShapes() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "it-rti-cpm:" + uid;
        String job = "it-rti-cpm-" + uid;
        try {
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(redis, cfg(job, prefix));
            RedisRuntimeCheckpointManager.PipelineKey pk =
                    new RedisRuntimeCheckpointManager.PipelineKey("topic-" + uid, "g");

            // jobName mismatch -> refused
            DefaultCheckpoint bad = new DefaultCheckpoint(1L, System.currentTimeMillis());
            bad.getStateSnapshot().putState("runtime:meta", Map.of("jobName", "someone-else"));
            assertFalse(mgr.restoreFromCheckpoint(bad, List.of(pk)));

            // runtime:state is not a map -> restoreState no-op; empty offsets -> restoreOffsets no-op
            DefaultCheckpoint weird = new DefaultCheckpoint(2L, System.currentTimeMillis());
            weird.getStateSnapshot().putState("runtime:state", "not-a-map");
            weird.getStateSnapshot().putState("runtime:offsets", Map.of());
            assertTrue(mgr.restoreFromCheckpoint(weird, List.of(pk)));

            // map-shaped state (post-serialization form) with null-ish entries and schema
            DefaultCheckpoint mapShaped = new DefaultCheckpoint(3L, System.currentTimeMillis());
            Map<Object, Object> state = new HashMap<>();
            state.put(prefix + ":plain", Map.of("f1", "v1"));
            HashMap<String, String> withNulls = new HashMap<>();
            withNulls.put(null, "skip-me");
            withNulls.put("f2", null);
            withNulls.put("f3", "v3");
            state.put(prefix + ":nullable", withNulls);
            state.put(Integer.valueOf(7), Map.of("x", "y"));
            mapShaped.getStateSnapshot().putState("runtime:state", state);
            mapShaped.getStateSnapshot().putState("runtime:stateSchema",
                    Map.of(prefix + ":plain", "java.lang.String|1"));
            mapShaped.getStateSnapshot().putState("runtime:offsets",
                    Map.of(pk.key(), Map.of(0, "  ")));
            assertTrue(mgr.restoreFromCheckpoint(mapShaped, List.of(pk)));

            RMap<String, String> plain = redis.getMap(prefix + ":plain", StringCodec.INSTANCE);
            assertEquals("v1", plain.get("f1"));
            RMap<String, String> nullable = redis.getMap(prefix + ":nullable", StringCodec.INSTANCE);
            assertEquals("v3", nullable.get("f3"));
            assertFalse(nullable.containsKey("f2"));
            RMap<String, String> schema = redis.getMap(prefix + ":" + job + ":stateSchema", StringCodec.INSTANCE);
            assertEquals("java.lang.String|1", schema.get(prefix + ":plain"));

            // offsets present but pipelines null -> tolerated
            DefaultCheckpoint noPipes = new DefaultCheckpoint(4L, System.currentTimeMillis());
            noPipes.getStateSnapshot().putState("runtime:offsets",
                    Map.of(pk.key(), Map.of(0, "5-0")));
            assertTrue(mgr.restoreFromCheckpoint(noPipes, null));
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.getKeys().deleteByPattern("stream:topic:topic-" + uid + "*");
            redis.shutdown();
        }
    }

    @Test
    void snapshotStateSkipsUnsupportedTypesAndMissingKeys() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "it-rti-cpm:" + uid;
        String job = "it-rti-cpm-" + uid;
        try {
            RedisKeyedStateStore<String> store = new RedisKeyedStateStore<>(redis,
                    new com.fasterxml.jackson.databind.ObjectMapper(), prefix, job, "t", "g", "op",
                    java.time.Duration.ZERO, 0, 1, 0L, java.time.Duration.ofMinutes(1),
                    false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);

            // registered but never created -> pruned from the index during snapshot
            store.registerStateKey(prefix + ":ghost");

            // unsupported redis type (SET) -> skipped with debug log
            RSet<String> set = redis.getSet(prefix + ":aset", StringCodec.INSTANCE);
            set.add("member");
            store.registerStateKey(prefix + ":aset");

            // empty zset/map values in a restore snapshot must be skipped
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(redis, cfg(job, prefix));
            Checkpoint cp = mgr.triggerCheckpoint(List.of());
            assertNotNull(cp);

            DefaultCheckpoint restoreShape = new DefaultCheckpoint(9L, System.currentTimeMillis());
            Map<String, Object> state = new HashMap<>();
            state.put(prefix + ":empty-z", RedisRuntimeCheckpointManager.RedisStateValue.ofZset(Map.of()));
            state.put(prefix + ":empty-m", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of()));
            state.put(" ", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("a", "b")));
            state.put(prefix + ":null-v", null);
            restoreShape.getStateSnapshot().putState("runtime:state", state);
            assertTrue(mgr.restoreFromCheckpoint(restoreShape, List.of()));

            RSet<String> index = redis.getSet(prefix + ":" + job + ":stateKeys", StringCodec.INSTANCE);
            assertFalse(index.contains(prefix + ":ghost"), "missing keys must be pruned from the index");
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }
}
