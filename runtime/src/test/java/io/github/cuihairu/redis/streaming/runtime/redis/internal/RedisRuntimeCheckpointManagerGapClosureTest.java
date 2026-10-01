package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.ScoredEntry;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branches of {@link RedisRuntimeCheckpointManager}: jobName-mismatch guard variants,
 * null entries in the checkpoint listing, offset override shapes, zset/map snapshot pruning arms
 * and the destructive restore arms (empty/null values, TTL application and null-type state values).
 */
class RedisRuntimeCheckpointManagerGapClosureTest {

    private RedissonClient redisson;
    private RSet<String> index;
    private RMap<String, String> schema;
    private RKeys rkeys;
    private RedisRuntimeConfig config;
    private RedisRuntimeConfig realConfig;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        index = mock(RSet.class);
        schema = mock(RMap.class);
        rkeys = mock(RKeys.class);
        when(redisson.getSet(anyString(), any(Codec.class))).thenReturn((RSet) index);
        when(redisson.getMap(anyString(), any(Codec.class))).thenReturn((RMap) schema);
        when(redisson.getKeys()).thenReturn(rkeys);
        when(rkeys.countExists(anyString())).thenReturn(1L);
        @SuppressWarnings("unchecked")
        RBucket<Object> bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn((RBucket) bucket);
        realConfig = RedisRuntimeConfig.builder()
                .jobName("cp-gap")
                .stateKeyPrefix("it-cp-gap")
                .checkpointKeyPrefix("it-cp-gap:cp")
                .checkpointsToKeep(0)
                .build();
        config = mock(RedisRuntimeConfig.class, delegatesTo(realConfig));
    }

    @AfterEach
    void reset() {
        // nothing global to restore
    }

    private RedisRuntimeCheckpointManager manager() {
        return new RedisRuntimeCheckpointManager(redisson, config);
    }

    private static void swapField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Checkpoint checkpointWith(Map<String, Object> snapshotValues) {
        Checkpoint cp = mock(Checkpoint.class);
        Checkpoint.StateSnapshot snap = mock(Checkpoint.StateSnapshot.class);
        when(cp.getCheckpointId()).thenReturn(42L);
        when(cp.getTimestamp()).thenReturn(1L);
        when(cp.getStateSnapshot()).thenReturn(snap);
        AtomicReference<Map<String, Object>> holder = new AtomicReference<>(snapshotValues);
        when(snap.getState(anyString())).thenAnswer(inv -> holder.get().get(inv.getArgument(0, String.class)));
        return cp;
    }

    @Test
    void restoreProceedsWhenMetaHasNoJobName() {
        Map<String, Object> snap = new HashMap<>();
        snap.put("runtime:meta", new HashMap<>(Map.of("stateKeyPrefix", "x")));
        RedisRuntimeCheckpointManager m = manager();
        assertTrue(m.restoreFromCheckpoint(checkpointWith(snap), List.of()));
    }

    @Test
    void restoreRejectsMismatchedJobName() {
        Map<String, Object> snap = new HashMap<>();
        snap.put("runtime:meta", new HashMap<>(Map.of("jobName", "other")));
        RedisRuntimeCheckpointManager m = manager();
        org.junit.jupiter.api.Assertions.assertFalse(m.restoreFromCheckpoint(checkpointWith(snap), List.of()));
    }

    @Test
    void sinkCommittedScanSkipsNullEntriesAndMarkerFailures() throws Exception {
        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        Checkpoint plain = checkpointWith(Map.of());
        Checkpoint committed = checkpointWith(Map.of("runtime:meta", Map.of("sinkCommitted", true)));
        when(storage.listCheckpoints(anyInt())).thenReturn(Arrays.asList(null, plain, committed));
        RedisRuntimeCheckpointManager m = manager();
        swapField(m, "storage", storage);

        RBucket<String> marker = mock(RBucket.class);
        when(marker.isExists()).thenReturn(false);
        when(redisson.getBucket(anyString(), any(Codec.class))).thenReturn((RBucket) marker);

        assertEquals(committed, m.getLatestSinkCommittedCheckpoint());
    }

    @Test
    void snapshotOffsetsUsesOverridesWherePresent() throws Exception {
        TopicPartitionRegistry registry = mock(TopicPartitionRegistry.class);
        when(registry.getPartitionCount("t")).thenReturn(3);
        RedisRuntimeCheckpointManager m = manager();
        swapField(m, "partitionRegistry", registry);
        RMap<String, String> frontier = mock(RMap.class);
        when(frontier.get("g")).thenReturn("9-9");
        when(redisson.getMap(anyString(), any(Codec.class))).thenReturn((RMap) frontier);

        Map<String, Map<Integer, String>> override = new HashMap<>();
        override.put("t|g", new HashMap<>(Map.of(0, "5-5", 1, "   ")));
        @SuppressWarnings("unchecked")
        Map<String, Map<Integer, String>> offsets =
                (Map<String, Map<Integer, String>>) m.triggerCheckpoint(7L,
                        List.of(new RedisRuntimeCheckpointManager.PipelineKey("t", "g")), override)
                        .getStateSnapshot().getState("runtime:offsets");
        assertEquals("5-5", offsets.get("t|g").get(0), "explicit override wins");
        assertEquals("9-9", offsets.get("t|g").get(1), "blank override falls back to committed frontier");
        assertEquals("9-9", offsets.get("t|g").get(2), "missing override falls back to committed frontier");
    }

    @Test
    void snapshotStatePrunesEmptyAndNullZsetEntries() throws Exception {
        when(index.readAll()).thenReturn((java.util.Set) java.util.Set.of("z-full", "z-empty", "m-full", "m-empty", " "));
        when(rkeys.getType("z-full")).thenReturn(RType.ZSET);
        when(rkeys.getType("z-empty")).thenReturn(RType.ZSET);
        when(rkeys.getType("m-full")).thenReturn(RType.MAP);
        when(rkeys.getType("m-empty")).thenReturn(RType.MAP);

        RScoredSortedSet<String> zFull = mock(RScoredSortedSet.class);
        when(zFull.entryRange(0, -1)).thenReturn((List) Arrays.asList(
                null, new ScoredEntry<>(1.0d, null), new ScoredEntry<>(2.5d, "v")));
        RScoredSortedSet<String> zEmpty = mock(RScoredSortedSet.class);
        when(zEmpty.entryRange(0, -1)).thenReturn((List) List.of());
        when(redisson.getScoredSortedSet(eq("z-full"), any(Codec.class))).thenReturn((RScoredSortedSet) zFull);
        when(redisson.getScoredSortedSet(eq("z-empty"), any(Codec.class))).thenReturn((RScoredSortedSet) zEmpty);

        RMap<String, String> mFull = mock(RMap.class);
        when(mFull.readAllMap()).thenReturn(Map.of("a", "b"));
        RMap<String, String> mEmpty = mock(RMap.class);
        when(mEmpty.readAllMap()).thenReturn(Map.of());
        when(redisson.getMap(eq("m-full"), any(Codec.class))).thenReturn((RMap) mFull);
        when(redisson.getMap(eq("m-empty"), any(Codec.class))).thenReturn((RMap) mEmpty);

        Checkpoint cp = manager().triggerCheckpoint(8L, List.of(), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) cp.getStateSnapshot().getState("runtime:state");
        assertInstanceOf(RedisRuntimeCheckpointManager.RedisStateValue.class, state.get("z-full"));
        assertEquals(Map.of("v", 2.5d),
                ((RedisRuntimeCheckpointManager.RedisStateValue) state.get("z-full")).zset());
        assertNull(state.get("z-empty"), "empty zsets are pruned from the snapshot");
        assertNotNull(state.get("m-full"));
        assertNull(state.get("m-empty"), "empty maps are pruned from the snapshot");
        verify(index, org.mockito.Mockito.atLeastOnce()).remove("z-empty");
        verify(index, org.mockito.Mockito.atLeastOnce()).remove("m-empty");
    }

    @Test
    void restoreStateHandlesEmptyNullTypedAndTtlArms() throws Exception {
        when(config.getStateTtl()).thenReturn(Duration.ofSeconds(20));
        Map<String, Object> rawState = new HashMap<>();
        rawState.put("   ", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("a", "b")));
        rawState.put("z-null", RedisRuntimeCheckpointManager.RedisStateValue.ofZset(null));
        rawState.put("z-empty", RedisRuntimeCheckpointManager.RedisStateValue.ofZset(Map.of()));
        rawState.put("z-full", RedisRuntimeCheckpointManager.RedisStateValue.ofZset(Map.of("v", 1.0d)));
        rawState.put("m-empty", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of()));
        rawState.put("m-full", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("a", "b")));
        rawState.put("null-type", nullTypedStateValue());
        rawState.put("as-map", Map.of("k", "v"));
        Map<String, Object> snap = new HashMap<>();
        snap.put("runtime:state", rawState);

        RScoredSortedSet<String> zset = mock(RScoredSortedSet.class);
        RMap<String, String> map = mock(RMap.class);
        RMap<String, String> asMap = mock(RMap.class);
        when(redisson.getScoredSortedSet(eq("z-full"), any(Codec.class))).thenReturn((RScoredSortedSet) zset);
        when(redisson.getMap(eq("m-full"), any(Codec.class))).thenReturn((RMap) map);
        when(redisson.getMap(eq("as-map"), any(Codec.class))).thenReturn((RMap) asMap);
        when(index.readAll()).thenReturn((java.util.Set) java.util.Set.of("stale"));

        RedisRuntimeCheckpointManager m = manager();
        assertTrue(m.restoreFromCheckpoint(checkpointWith(snap), List.of()));

        verify(zset).addAll(Map.of("v", 1.0d));
        verify(zset).expire((Duration) Duration.ofSeconds(20));
        verify(map).putAll(Map.of("a", "b"));
        verify(map).expire((Duration) Duration.ofSeconds(20));
        verify(asMap).putAll(Map.of("k", "v"));
        verify(rkeys).delete("stale");
        verify(index).clear();
        verify(schema).clear();
    }

    @Test
    void restoreStateSkipsExpireWhenTtlIsZeroOrMissing() throws Exception {
        Map<String, Object> rawState = Map.of(
                "z-full", RedisRuntimeCheckpointManager.RedisStateValue.ofZset(Map.of("v", 1.0d)),
                "m-full", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("a", "b")));
        Map<String, Object> snap = Map.of("runtime:state", rawState);
        RScoredSortedSet<String> zset = mock(RScoredSortedSet.class);
        RMap<String, String> map = mock(RMap.class);
        when(redisson.getScoredSortedSet(eq("z-full"), any(Codec.class))).thenReturn((RScoredSortedSet) zset);
        when(redisson.getMap(eq("m-full"), any(Codec.class))).thenReturn((RMap) map);

        when(config.getStateTtl()).thenReturn(Duration.ZERO);
        assertTrue(manager().restoreFromCheckpoint(checkpointWith(snap), List.of()));
        verify(zset, never()).expire(any(Duration.class));

        when(config.getStateTtl()).thenReturn(null);
        assertTrue(manager().restoreFromCheckpoint(checkpointWith(snap), List.of()));
        verify(zset, never()).expire(any(Duration.class));
        verify(map, never()).expire(any(Duration.class));
    }

    private static Object nullTypedStateValue() throws Exception {
        Constructor<?> ctor = RedisRuntimeCheckpointManager.RedisStateValue.class
                .getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(new Object[]{null, null, null});
    }

    @Test
    void restoreRejectsNullCheckpoint() {
        org.junit.jupiter.api.Assertions.assertFalse(manager().restoreFromCheckpoint(null, List.of()));
    }

    @Test
    void triggerCheckpointAttachesFencingTokenWhenElectorPresent() {
        RedisLeaderElector elector = mock(RedisLeaderElector.class);
        when(elector.currentFencingToken()).thenReturn(9L);
        RedisRuntimeCheckpointManager m = new RedisRuntimeCheckpointManager(redisson, config, elector);

        Checkpoint cp = m.triggerCheckpoint(11L, List.of(), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) cp.getStateSnapshot().getState("runtime:meta");
        assertEquals(9L, meta.get("fencingToken"));
    }

    @Test
    void triggerCheckpointOmitsFencingTokenWhenElectorAbsent() {
        Checkpoint cp = manager().triggerCheckpoint(12L, List.of(), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) cp.getStateSnapshot().getState("runtime:meta");
        assertTrue(meta == null || !meta.containsKey("fencingToken"));
    }

    @Test
    void restoreScanKeepsOnlyMaxFencingTokenCheckpoints() throws Exception {
        Checkpoint stale = checkpointWith(Map.of("runtime:meta", Map.of("fencingToken", 3L)));
        when(stale.getCheckpointId()).thenReturn(50L);
        Checkpoint fresh = checkpointWith(Map.of("runtime:meta", Map.of("fencingToken", 7L)));
        when(fresh.getCheckpointId()).thenReturn(40L);
        Checkpoint preElection = checkpointWith(Map.of("runtime:meta", Map.of("jobName", "cp-gap")));
        when(preElection.getCheckpointId()).thenReturn(60L);

        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenReturn(List.of(stale, fresh, preElection));
        RedisRuntimeCheckpointManager m = managerWithElector(storage, 7L);

        // newest by id (60) carries no token and must not win; the max-token (7) checkpoint does
        assertEquals(40L, m.getLatestCheckpointForRestore().getCheckpointId());
    }

    @Test
    void restoreScanWithoutTokensFallsBackToFullHistory() throws Exception {
        Checkpoint older = checkpointWith(Map.of("runtime:meta", Map.of("jobName", "cp-gap")));
        when(older.getCheckpointId()).thenReturn(10L);
        Checkpoint newer = checkpointWith(Map.of("runtime:meta", Map.of("jobName", "cp-gap")));
        when(newer.getCheckpointId()).thenReturn(20L);

        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenReturn(List.of(newer, older));
        RedisRuntimeCheckpointManager m = managerWithElector(storage, 0L);

        assertEquals(20L, m.getLatestCheckpointForRestore().getCheckpointId());
    }

    @Test
    void sinkCommittedScanRejectsStaleFencingTokenCheckpoints() throws Exception {
        Checkpoint stale = checkpointWith(Map.of("runtime:meta", Map.of("fencingToken", 3L, "sinkCommitted", true)));
        when(stale.getCheckpointId()).thenReturn(50L);
        Checkpoint fresh = checkpointWith(Map.of("runtime:meta", Map.of("fencingToken", 7L)));
        when(fresh.getCheckpointId()).thenReturn(40L);

        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenReturn(List.of(stale, fresh));
        RedisRuntimeCheckpointManager m = managerWithElector(storage, 7L);

        RBucket<String> marker = mock(RBucket.class);
        when(marker.isExists()).thenReturn(false);
        when(redisson.getBucket(anyString(), any(Codec.class))).thenReturn((RBucket) marker);

        // the sink-committed checkpoint carries a stale token (3 < 7) and is skipped
        assertNull(m.getLatestSinkCommittedCheckpoint());
    }

    @Test
    void getTxnHandlesReturnsEmptyWhenSnapshotReadFails() {
        Checkpoint cp = mock(Checkpoint.class);
        Checkpoint.StateSnapshot snap = mock(Checkpoint.StateSnapshot.class);
        when(cp.getCheckpointId()).thenReturn(42L);
        when(cp.getStateSnapshot()).thenReturn(snap);
        when(snap.getState(anyString())).thenThrow(new RuntimeException("corrupt snapshot"));

        assertEquals(Map.of(), manager().getTxnHandles(cp));
    }

    @Test
    void sinkCommittedAndInDoubtScansSwallowStorageFailures() throws Exception {
        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenThrow(new RuntimeException("redis down"));
        RedisRuntimeCheckpointManager m = manager();
        swapField(m, "storage", storage);

        assertNull(m.getLatestSinkCommittedCheckpoint());
        assertNull(m.getLatestInDoubtTwoPhaseCheckpoint());
    }

    @Test
    void inDoubtScanReturnsNullWhenNewestIsSinkCommitted() throws Exception {
        Checkpoint committed = checkpointWith(Map.of("runtime:meta", Map.of("sinkCommitted", true)));
        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenReturn(List.of(committed));
        RedisRuntimeCheckpointManager m = manager();
        swapField(m, "storage", storage);

        RBucket<String> marker = mock(RBucket.class);
        when(marker.isExists()).thenReturn(false);
        when(redisson.getBucket(anyString(), any(Codec.class))).thenReturn((RBucket) marker);

        assertNull(m.getLatestInDoubtTwoPhaseCheckpoint());
    }

    @Test
    void restoreScanTreatsUnreadableMetaAsTokenZero() throws Exception {
        Checkpoint broken = mock(Checkpoint.class);
        Checkpoint.StateSnapshot snap = mock(Checkpoint.StateSnapshot.class);
        when(broken.getCheckpointId()).thenReturn(9L);
        when(broken.getStateSnapshot()).thenReturn(snap);
        when(snap.getState(anyString())).thenThrow(new RuntimeException("corrupt meta"));
        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenReturn(List.of(broken));
        RedisRuntimeCheckpointManager m = managerWithElector(storage, 0L);

        assertNotNull(m.getLatestCheckpointForRestore());
    }

    @Test
    void restoreFromLatestReturnsNullWhenRestoreRejectsCheckpoint() throws Exception {
        Checkpoint mismatched = checkpointWith(Map.of("runtime:meta", Map.of("jobName", "other")));
        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenReturn(List.of(mismatched));
        RedisRuntimeCheckpointManager m = manager();
        swapField(m, "storage", storage);

        assertNull(m.restoreFromLatestCheckpointOrNull(List.of()));
    }

    @Test
    void cleanupOldSwallowsDeleteFailures() throws Exception {
        RedisRuntimeConfig keepOne = RedisRuntimeConfig.builder()
                .jobName("cp-gap")
                .stateKeyPrefix("it-cp-gap")
                .checkpointKeyPrefix("it-cp-gap:cp")
                .checkpointsToKeep(1)
                .build();
        RedisRuntimeConfig cfg = mock(RedisRuntimeConfig.class, delegatesTo(keepOne));
        RedisRuntimeCheckpointManager m = new RedisRuntimeCheckpointManager(redisson, cfg);

        Checkpoint first = checkpointWith(Map.of());
        Checkpoint second = checkpointWith(Map.of());
        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenReturn(List.of(first, second));
        when(storage.deleteCheckpoint(anyLong())).thenThrow(new RuntimeException("redis down"));
        swapField(m, "storage", storage);

        // 2 retained > keep 1: the eviction loop runs and the delete failure is swallowed
        m.cleanupOld();
    }

    @Test
    void takeoverRefreshRaisesLocalIdCounterToStorageMax() throws Exception {
        Checkpoint older = checkpointWith(Map.of());
        when(older.getCheckpointId()).thenReturn(4L);
        Checkpoint newest = checkpointWith(Map.of());
        when(newest.getCheckpointId()).thenReturn(8L);
        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenReturn(Arrays.asList(null, older, newest));
        RedisRuntimeCheckpointManager m = manager();
        swapField(m, "storage", storage);

        // the constructor's snapshot (empty storage) left the counter behind storage's max
        assertTrue(m.allocateCheckpointId() < 9, "counter must still hold the stale startup snapshot");
        m.refreshCheckpointIdFromStorage();
        assertEquals(9L, m.allocateCheckpointId(), "takeover must not re-allocate the dead leader's ids");
        assertEquals(10L, m.allocateCheckpointId(), "counter keeps advancing after the refresh");
    }

    @Test
    void takeoverRefreshNeverLowersLocalCounter() throws Exception {
        Checkpoint old = checkpointWith(Map.of());
        when(old.getCheckpointId()).thenReturn(8L);
        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenReturn(List.of(old));
        RedisRuntimeCheckpointManager m = manager();
        swapField(m, "storage", storage);
        swapField(m, "nextCheckpointId", new java.util.concurrent.atomic.AtomicLong(12L));

        m.refreshCheckpointIdFromStorage();
        assertEquals(12L, m.allocateCheckpointId(), "refresh must only ever move the counter forward");
    }

    @Test
    void takeoverRefreshKeepsLocalCounterWhenStorageUnavailable() throws Exception {
        RedisCheckpointStorage storage = mock(RedisCheckpointStorage.class);
        when(storage.listCheckpoints(anyInt())).thenThrow(new RuntimeException("redis down"));
        RedisRuntimeCheckpointManager m = manager();
        swapField(m, "storage", storage);
        swapField(m, "nextCheckpointId", new java.util.concurrent.atomic.AtomicLong(5L));

        m.refreshCheckpointIdFromStorage();
        assertEquals(5L, m.allocateCheckpointId(), "storage outage must keep the local counter untouched");
    }

    private RedisRuntimeCheckpointManager managerWithElector(RedisCheckpointStorage storage, long token)
            throws Exception {
        RedisLeaderElector elector = mock(RedisLeaderElector.class);
        when(elector.currentFencingToken()).thenReturn(token);
        RedisRuntimeCheckpointManager m = new RedisRuntimeCheckpointManager(redisson, config, elector);
        swapField(m, "storage", storage);
        return m;
    }
}
