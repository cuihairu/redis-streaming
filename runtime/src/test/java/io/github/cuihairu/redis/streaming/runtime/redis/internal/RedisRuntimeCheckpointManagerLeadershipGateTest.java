package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The leadership gate on {@link RedisRuntimeCheckpointManager#triggerCheckpoint}: when leader
 * election is enabled, the store must confirm the lease (renew) immediately before writing —
 * a leader that lost its lease mid-checkpoint (GC pause, partition, takeover) must land NO
 * snapshot, instead of the old behavior of writing an unfenced orphan that restore has to
 * reject by token afterwards. Without an elector the path is unchanged.
 */
class RedisRuntimeCheckpointManagerLeadershipGateTest {

    private static final String JOB = "job-lg";
    private static final String PREFIX = "it-lg-cpm";
    private static final String STORAGE_PREFIX = PREFIX + ":cp" + JOB + ":";

    private RedissonClient redisson;
    private RSet<String> index;
    private final Map<String, RMap<String, String>> maps = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        RKeys rkeys = mock(RKeys.class);
        index = (RSet<String>) mock(RSet.class);
        RScript script = mock(RScript.class);
        when(redisson.getKeys()).thenReturn(rkeys);
        when(redisson.getSet(anyString(), any(Codec.class))).thenReturn((RSet) index);
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        when(redisson.getMap(anyString(), any(Codec.class)))
                .thenAnswer(inv -> mapNamed(inv.getArgument(0)));
        when(redisson.getMap(anyString()))
                .thenAnswer(inv -> mapNamed(inv.getArgument(0)));
        when(redisson.getScoredSortedSet(anyString(), any(Codec.class)))
                .thenAnswer(inv -> mock(RScoredSortedSet.class));
        when(redisson.getBucket(anyString()))
                .thenAnswer(inv -> mock(RBucket.class));
        when(redisson.getBucket(anyString(), any(Codec.class)))
                .thenAnswer(inv -> mock(RBucket.class));
        when(rkeys.getKeys()).thenReturn(List.of());
        when(index.readAll()).thenReturn(new HashSet<>());
    }

    private RMap<String, String> mapNamed(String name) {
        return maps.computeIfAbsent(name, k -> mock(RMap.class));
    }

    private RedisRuntimeConfig cfg() {
        return RedisRuntimeConfig.builder()
                .jobName(JOB)
                .stateKeyPrefix(PREFIX)
                .checkpointKeyPrefix(PREFIX + ":cp")
                .stateTtl(Duration.ZERO)
                .checkpointsToKeep(5)
                .build();
    }

    private static RedisRuntimeCheckpointManager.PipelineKey pipeline() {
        return new RedisRuntimeCheckpointManager.PipelineKey("t-lg", "g-lg");
    }

    @Test
    void storeIsRefusedWhenAHeldLeadershipLeaseWasLost() {
        RedisLeaderElector elector = mock(RedisLeaderElector.class);
        when(elector.renewLeadership()).thenReturn(false);
        when(elector.hasHeldLeadership()).thenReturn(true);
        RedisRuntimeCheckpointManager manager = new RedisRuntimeCheckpointManager(redisson, cfg(), elector);
        long id = manager.allocateCheckpointId();

        Checkpoint cp = manager.triggerCheckpoint(id, List.of(pipeline()), null);

        assertNull(cp, "a lease lost mid-checkpoint must refuse the store instead of writing an orphan snapshot");
        verify(elector).renewLeadership();
        verify(redisson, never()).getBucket(STORAGE_PREFIX + id);
    }

    @Test
    void followerManualCheckpointKeepsItsPathWhenItNeverHeldTheLease() {
        RedisLeaderElector elector = mock(RedisLeaderElector.class);
        when(elector.renewLeadership()).thenReturn(false);
        when(elector.hasHeldLeadership()).thenReturn(false);
        when(elector.currentFencingToken()).thenReturn(5L);
        RedisRuntimeCheckpointManager manager = new RedisRuntimeCheckpointManager(redisson, cfg(), elector);

        Checkpoint cp = manager.triggerCheckpoint(manager.allocateCheckpointId(), List.of(pipeline()), null);

        assertNotNull(cp, "a follower's manual checkpoint is a designed path under the shared epoch token");
        verify(redisson).getBucket(STORAGE_PREFIX + cp.getCheckpointId());
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = cp.getStateSnapshot().getState("runtime:meta");
        assertEquals(5L, ((Number) meta.get("fencingToken")).longValue());
    }

    @Test
    void storeProceedsAndCarriesTheTermTokenWhenLeadershipRenews() {
        RedisLeaderElector elector = mock(RedisLeaderElector.class);
        when(elector.renewLeadership()).thenReturn(true);
        when(elector.currentFencingToken()).thenReturn(7L);
        RedisRuntimeCheckpointManager manager = new RedisRuntimeCheckpointManager(redisson, cfg(), elector);

        Checkpoint cp = manager.triggerCheckpoint(manager.allocateCheckpointId(), List.of(pipeline()), null);

        assertNotNull(cp);
        verify(elector).renewLeadership();
        verify(redisson).getBucket(STORAGE_PREFIX + cp.getCheckpointId());
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = cp.getStateSnapshot().getState("runtime:meta");
        assertEquals(7L, ((Number) meta.get("fencingToken")).longValue());
    }

    @Test
    void storeProceedsUnchangedWithoutLeaderElector() {
        RedisRuntimeCheckpointManager manager = new RedisRuntimeCheckpointManager(redisson, cfg());

        Checkpoint cp = manager.triggerCheckpoint(manager.allocateCheckpointId(), List.of(pipeline()), null);

        assertNotNull(cp, "with leader election disabled the gate must not interfere");
        verify(redisson).getBucket(STORAGE_PREFIX + cp.getCheckpointId());
    }
}
