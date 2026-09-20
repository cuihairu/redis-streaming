package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Offsets-from-frontier snapshotting, marker edge cases and restore failure tolerance. */
@Tag("integration")
class CheckpointManagerDeepBranchIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void snapshotReadsRealFrontiersAndRestoreToleratesMissingGroups() throws Exception {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "cdeep-" + uid;
        String topic = "cdeep-" + uid;
        String group = "g1";
        try {
            RStream<String, Object> stream = redis.getStream(
                    io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0));
            stream.add(StreamAddArgs.entries(Map.of("payload", "x")));
            stream.createGroup(StreamCreateGroupArgs.name(group).id(new StreamMessageId(0, 0)).makeStream());

            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                    .jobName("cdeep-" + uid).stateKeyPrefix(prefix).build();
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(redis, cfg);
            RedisRuntimeCheckpointManager.PipelineKey pk =
                    new RedisRuntimeCheckpointManager.PipelineKey(topic, group);

            Checkpoint cp = mgr.triggerCheckpoint(List.of(pk));
            assertNotNull(cp);
            @SuppressWarnings("unchecked")
            Map<String, Map<Integer, String>> offsets = cp.getStateSnapshot().getState("runtime:offsets");
            assertNotNull(offsets, "offsets snapshot should exist");
            assertNotNull(offsets.get(pk.key()));

            // restore against existing group must succeed
            assertTrue(mgr.restoreFromCheckpoint(cp, List.of(pk)));

            // marker semantics
            assertFalse(mgr.isSinkCommittedMarkerPresent(999999L));
            assertTrue(mgr.markSinkCommittedMarker(999999L));
            assertTrue(mgr.isSinkCommittedMarkerPresent(999999L));
            assertFalse(mgr.markSinkCommitted(null));
            assertFalse(mgr.restoreFromCheckpoint(null, List.of(pk)));

            // stream without group for the pipeline key must still snapshot (frontier read tolerated)
            Checkpoint cp2 = mgr.triggerCheckpoint(List.of(new RedisRuntimeCheckpointManager.PipelineKey("ghost-" + uid, group)));
            assertNotNull(cp2);
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.shutdown();
        }
    }

    @Test
    void cleanupKeepsOnlyNAndHandlesEmptyStorage() {
        RedissonClient redis = client();
        String uid = UUID.randomUUID().toString().substring(0, 8);
        String prefix = "cclean-" + uid;
        try {
            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                    .jobName("cclean-" + uid).stateKeyPrefix(prefix).checkpointsToKeep(2).build();
            RedisRuntimeCheckpointManager mgr = new RedisRuntimeCheckpointManager(redis, cfg);
            assertNull(mgr.getLatestCheckpoint());
            Checkpoint first = mgr.triggerCheckpoint(List.of());
            Checkpoint second = mgr.triggerCheckpoint(List.of());
            Checkpoint third = mgr.triggerCheckpoint(List.of());
            assertEquals(third.getCheckpointId(), mgr.getLatestCheckpoint().getCheckpointId());
            assertNotNull(first);
            assertNotNull(second);
        } finally {
            redis.getKeys().deleteByPattern(prefix + "*");
            redis.shutdown();
        }
    }
}
