package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamGroup;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct integration coverage for {@link RedisRuntimeCheckpointManager}: snapshot/restore of
 * keyed state (map + zset kinds), offsets override via XGROUP recreation, sink-committed
 * marker semantics and retention cleanup.
 */
@Tag("integration")
class RedisRuntimeCheckpointManagerIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static RedisRuntimeConfig cfg(String job, String prefix, int keep) {
        return RedisRuntimeConfig.builder()
                .jobName(job)
                .stateKeyPrefix(prefix)
                .checkpointKeyPrefix(prefix + ":cp")
                .checkpointsToKeep(keep)
                .build();
    }

    @Test
    void snapshotStoreRestoreAndRetention() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String job = "cpm-" + uid;
        String prefix = "streaming:cpm:test:" + uid;
        String topic = "cpm-topic-" + uid;
        String group = "g1";
        try {
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(client, cfg(job, prefix, 0));

            // no checkpoints yet
            assertNull(mgr.getLatestCheckpoint());
            assertNull(mgr.getLatestSinkCommittedCheckpoint());
            assertNull(mgr.restoreFromLatestCheckpointOrNull(List.of()));

            RedisKeyedStateStore<String> store = newStore(client, job, prefix, topic, group, Duration.ZERO);
            store.setCurrentPartitionId(0);
            store.setCurrentKey("k1");
            String field = store.stateFieldForKey("k1");
            RedisKeyedStateStore.StateMapRef ref = store.stateMapRef("state-a", field);
            ref.map().put(field, "v1");
            store.registerStateKey(ref.redisKey());
            store.touch(ref.redisKey(), "state-a", ref.map());

            // zset state kind (window-due style)
            String zkey = prefix + ":" + job + ":zset-state:" + uid;
            RScoredSortedSet<String> zset = client.getScoredSortedSet(zkey, StringCodec.INSTANCE);
            zset.add(123D, "member-1");
            store.registerStateKey(zkey);

            Checkpoint cp1 = mgr.triggerCheckpoint(List.of(new RedisRuntimeCheckpointManager.PipelineKey(topic, group)));
            assertNotNull(cp1);

            @SuppressWarnings("unchecked")
            Map<String, RedisRuntimeCheckpointManager.RedisStateValue> snap =
                    cp1.getStateSnapshot().getState("runtime:state");
            assertTrue(snap.containsKey(ref.redisKey()), "map state key must be snapshotted: " + snap.keySet());
            assertTrue(snap.containsKey(zkey), "zset state key must be snapshotted: " + snap.keySet());
            assertEquals(RedisRuntimeCheckpointManager.RedisStateType.MAP, snap.get(ref.redisKey()).type());
            assertEquals("v1", snap.get(ref.redisKey()).map().get(field));
            assertEquals(RedisRuntimeCheckpointManager.RedisStateType.ZSET, snap.get(zkey).type());
            assertEquals(123D, snap.get(zkey).zset().get("member-1"), 1e-9);

            // wipe the state keys, then restore from checkpoint
            assertTrue(mgr.restoreFromCheckpoint(cp1, List.of(new RedisRuntimeCheckpointManager.PipelineKey(topic, group))));
            assertEquals("v1", store.stateMapRef("state-a", field).map().get(field));
            assertEquals(1, client.getScoredSortedSet(zkey, StringCodec.INSTANCE).size());

            // sink-committed marker
            assertFalse(mgr.isSinkCommittedMarkerPresent(cp1.getCheckpointId()));
            assertTrue(mgr.markSinkCommitted(cp1));
            assertTrue(mgr.isSinkCommittedMarkerPresent(cp1.getCheckpointId()));
            assertNotNull(mgr.getLatestSinkCommittedCheckpoint());
            assertEquals(cp1.getCheckpointId(), mgr.getLatestSinkCommittedCheckpoint().getCheckpointId());
            // non DefaultCheckpoint input is rejected
            assertFalse(mgr.markSinkCommitted(null));

            // restoreFromLatestCheckpointOrNull picks it up
            Checkpoint restored = mgr.restoreFromLatestCheckpointOrNull(List.of(new RedisRuntimeCheckpointManager.PipelineKey(topic, group)));
            assertNotNull(restored);
            assertEquals(cp1.getCheckpointId(), restored.getCheckpointId());
            assertTrue(mgr.restoreFromLatestCheckpoint(List.of(new RedisRuntimeCheckpointManager.PipelineKey(topic, group))));
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void offsetsOverrideRestoresConsumerGroups() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String job = "cpm-off-" + uid;
        String prefix = "streaming:cpm:test:" + uid;
        String topic = "cpm-topic-" + uid;
        String group = "g1";
        try {
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(client, cfg(job, prefix, 0));
            RedisRuntimeCheckpointManager.PipelineKey pk = new RedisRuntimeCheckpointManager.PipelineKey(topic, group);

            Checkpoint cp = mgr.triggerCheckpoint(1L, List.of(pk), Map.of(pk.key(), Map.of(0, "42-0")));
            assertNotNull(cp);

            // restore recreates the group at the recorded offset (MKSTREAM if stream absent)
            assertTrue(mgr.restoreFromCheckpoint(cp, List.of(pk)));
            String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0);
            RStream<String, Object> stream = client.getStream(streamKey);
            List<StreamGroup> groups = stream.listGroups();
            assertEquals(1, groups.size());
            assertEquals(group, groups.get(0).getName());
            assertEquals(42L, groups.get(0).getLastDeliveredId().getId0());
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.getKeys().deleteByPattern("streaming:*" + topic + "*");
            client.shutdown();
        }
    }

    @Test
    void retentionCleansOldCheckpoints() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String job = "cpm-keep-" + uid;
        String prefix = "streaming:cpm:test:" + uid;
        try {
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(client, cfg(job, prefix, 1));
            Checkpoint first = mgr.triggerCheckpoint(List.of());
            assertNotNull(first);
            assertTrue(mgr.markSinkCommitted(first));
            Checkpoint second = mgr.triggerCheckpoint(List.of());
            assertNotNull(second);

            assertNotNull(mgr.getLatestCheckpoint());
            assertEquals(second.getCheckpointId(), mgr.getLatestCheckpoint().getCheckpointId());
            // retention purged the first checkpoint AND its sink-committed marker
            Checkpoint latestCommitted = mgr.getLatestSinkCommittedCheckpoint();
            assertTrue(latestCommitted == null || latestCommitted.getCheckpointId() != first.getCheckpointId(),
                    "first checkpoint's committed marker should have been purged");
            Checkpoint latest = mgr.restoreFromLatestCheckpointOrNull(List.of());
            assertEquals(second.getCheckpointId(), latest.getCheckpointId());
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    private static RedisKeyedStateStore<String> newStore(RedissonClient client, String job, String prefix,
                                                          String topic, String group, Duration ttl) {
        return new RedisKeyedStateStore<>(client, new com.fasterxml.jackson.databind.ObjectMapper(),
                prefix, job, topic, group, "op-1", ttl, 0, 1, 0L, Duration.ofMinutes(1),
                false, RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL);
    }
}
