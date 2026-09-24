package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.ScoredEntry;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fault-injection branches of {@link RedisRuntimeCheckpointManager} driven with a mocked
 * {@link RedissonClient}: partial failures inside snapshot/restore state walks (index read/write,
 * key pruning, TTL refresh), offset snapshot/restore error arms, sink-committed marking failure,
 * cleanup-time delete/marker failures and the restore refusal path.
 */
class RedisRuntimeCheckpointManagerFaultInjectionTest {

    private static final String JOB = "job-r2";
    private static final String PREFIX = "it-r2-cpm";
    private static final String INDEX_KEY = PREFIX + ":" + JOB + ":stateKeys";
    private static final String SCHEMA_KEY = PREFIX + ":" + JOB + ":stateSchema";
    private static final String STORAGE_PREFIX = PREFIX + ":cp" + JOB + ":";

    private RedissonClient redisson;
    private RKeys rkeys;
    private RSet<String> index;
    private RScript script;
    private final Map<String, RMap<String, String>> maps = new ConcurrentHashMap<>();
    private final Map<String, RScoredSortedSet<String>> zsets = new ConcurrentHashMap<>();
    private final Map<String, RBucket<Object>> buckets = new ConcurrentHashMap<>();
    private final Map<String, RBucket<String>> markers = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        rkeys = mock(RKeys.class);
        index = (RSet<String>) mock(RSet.class);
        script = mock(RScript.class);
        when(redisson.getKeys()).thenReturn(rkeys);
        when(redisson.getSet(anyString(), any(Codec.class))).thenReturn((RSet) index);
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        when(redisson.getMap(anyString(), any(Codec.class)))
                .thenAnswer(inv -> mapNamed(inv.getArgument(0)));
        when(redisson.getMap(anyString()))
                .thenAnswer(inv -> mapNamed(inv.getArgument(0)));
        when(redisson.getScoredSortedSet(anyString(), any(Codec.class)))
                .thenAnswer(inv -> zsets.computeIfAbsent(inv.getArgument(0), k -> mock(RScoredSortedSet.class)));
        when(redisson.getBucket(anyString()))
                .thenAnswer(inv -> buckets.computeIfAbsent(inv.getArgument(0), k -> mock(RBucket.class)));
        when(redisson.getBucket(anyString(), any(Codec.class)))
                .thenAnswer(inv -> markers.computeIfAbsent(inv.getArgument(0), k -> mock(RBucket.class)));
        when(rkeys.getKeys()).thenReturn(List.of());
        when(index.readAll()).thenReturn(new java.util.HashSet<>());
    }

    private RMap<String, String> mapNamed(String name) {
        return maps.computeIfAbsent(name, k -> mock(RMap.class));
    }

    private RedisRuntimeConfig cfg(Duration stateTtl, int keep) {
        return RedisRuntimeConfig.builder()
                .jobName(JOB)
                .stateKeyPrefix(PREFIX)
                .checkpointKeyPrefix(PREFIX + ":cp")
                .stateTtl(stateTtl)
                .checkpointsToKeep(keep)
                .build();
    }

    private RedisRuntimeCheckpointManager manager(Duration stateTtl, int keep) {
        return new RedisRuntimeCheckpointManager(redisson, cfg(stateTtl, keep));
    }

    private static DefaultCheckpoint checkpoint(long id, Map<String, Object> meta, Object state,
                                                Map<String, String> schemaSnap,
                                                Map<String, Map<Integer, String>> offsets) {
        DefaultCheckpoint cp = new DefaultCheckpoint(id, System.currentTimeMillis());
        if (meta != null) {
            cp.getStateSnapshot().putState("runtime:meta", meta);
        }
        if (state != null) {
            cp.getStateSnapshot().putState("runtime:state", state);
        }
        if (schemaSnap != null) {
            cp.getStateSnapshot().putState("runtime:stateSchema", schemaSnap);
        }
        if (offsets != null) {
            cp.getStateSnapshot().putState("runtime:offsets", offsets);
        }
        return cp;
    }

    private static RedisRuntimeCheckpointManager.RedisStateValue nullTypedStateValue() throws Exception {
        Constructor<RedisRuntimeCheckpointManager.RedisStateValue> ctor =
                RedisRuntimeCheckpointManager.RedisStateValue.class.getDeclaredConstructor(
                        RedisRuntimeCheckpointManager.RedisStateType.class, Map.class, Map.class);
        ctor.setAccessible(true);
        return ctor.newInstance(null, Map.of("a", "b"), null);
    }

    @Test
    void markSinkCommittedReturnsFalseWhenStorageWriteThrows() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        DefaultCheckpoint cp = checkpoint(11L, Map.of("jobName", JOB), null, null, null);
        when(redisson.getBucket(STORAGE_PREFIX + "11"))
                .thenThrow(new IllegalStateException("bucket down"));
        assertFalse(manager.markSinkCommitted(cp));
    }

    @Test
    void restoreFromCheckpointReturnsFalseWhenStateIndexUnavailable() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        Map<String, Object> state = new HashMap<>();
        state.put(PREFIX + ":k", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        DefaultCheckpoint cp = checkpoint(12L, Map.of("jobName", JOB), state, null, null);
        when(redisson.getSet(anyString(), any(Codec.class)))
                .thenThrow(new IllegalStateException("index down"));
        assertFalse(manager.restoreFromCheckpoint(cp, List.of()));
    }

    @Test
    void getLatestSinkCommittedSkipsBrokenCheckpointIds() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        Checkpoint evil = mock(Checkpoint.class);
        when(evil.getTimestamp()).thenReturn(5L);
        when(evil.getCheckpointId()).thenThrow(new IllegalStateException("id explosion"));
        Checkpoint.StateSnapshot snapshot = mock(Checkpoint.StateSnapshot.class);
        when(evil.getStateSnapshot()).thenReturn(snapshot);
        when(snapshot.getState("runtime:meta")).thenReturn(null);
        when(rkeys.getKeys()).thenReturn(List.of(STORAGE_PREFIX + "21"));
        RBucket<Object> bucket = buckets.computeIfAbsent(STORAGE_PREFIX + "21", k -> mock(RBucket.class));
        when(bucket.get()).thenReturn(evil);
        assertNull(manager.getLatestSinkCommittedCheckpoint());
    }

    @Test
    void snapshotOffsetsCoversOverrideBlankAndFrontierErrorArms() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        RedisRuntimeCheckpointManager.PipelineKey a =
                new RedisRuntimeCheckpointManager.PipelineKey("t-a", "g");
        RedisRuntimeCheckpointManager.PipelineKey b =
                new RedisRuntimeCheckpointManager.PipelineKey("t-b", "g");
        when(mapNamed(StreamKeys.topicMeta("t-a")).get("partitionCount")).thenReturn("2");
        when(mapNamed(StreamKeys.topicMeta("t-b")).get("partitionCount")).thenReturn("1");
        when(mapNamed(StreamKeys.commitFrontier("t-a", 1)).get("g"))
                .thenThrow(new IllegalStateException("frontier down"));
        when(mapNamed(StreamKeys.commitFrontier("t-b", 0)).get("g")).thenReturn("3-3");

        Map<String, Map<Integer, String>> override = new HashMap<>();
        Map<Integer, String> aOverrides = new HashMap<>();
        aOverrides.put(0, "9-9");
        aOverrides.put(1, " ");
        override.put(a.key(), aOverrides);

        Checkpoint cp = manager.triggerCheckpoint(manager.allocateCheckpointId(), List.of(a, b), override);
        assertNotNull(cp);

        @SuppressWarnings("unchecked")
        Map<String, Map<Integer, String>> offsets =
                cp.getStateSnapshot().getState("runtime:offsets");
        assertEqualsOffset(offsets.get(a.key()), 0, "9-9");
        assertTrue(offsets.get(a.key()).containsKey(1), "failed frontier read still records the partition");
        assertEqualsOffset(offsets.get(b.key()), 0, "3-3");
    }

    private static void assertEqualsOffset(Map<Integer, String> per, int pid, String expected) {
        assertNotNull(per);
        assertTrue(expected.equals(per.get(pid)), "expected " + expected + " for pid " + pid + " but got " + per);
    }

    @Test
    void snapshotStateCoversJunkIndexEntriesAndErrorArms() throws Exception {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        when(index.readAll()).thenReturn(new java.util.HashSet<>(Arrays.asList(null, " ", "pk", "gk", "zk",
                "zke-t", "zke-s", "mk", "mke-t", "mke-s", "bad", "setk")));
        when(rkeys.countExists("pk")).thenReturn(0L);
        when(rkeys.countExists("gk")).thenReturn(1L);
        when(rkeys.countExists("zk")).thenReturn(1L);
        when(rkeys.countExists("zke-t")).thenReturn(1L);
        when(rkeys.countExists("zke-s")).thenReturn(1L);
        when(rkeys.countExists("mk")).thenReturn(1L);
        when(rkeys.countExists("mke-t")).thenReturn(1L);
        when(rkeys.countExists("mke-s")).thenReturn(1L);
        when(rkeys.countExists("bad")).thenReturn(1L);
        when(rkeys.countExists("setk")).thenReturn(1L);
        doThrow(new IllegalStateException("index remove down")).when(index).remove("pk");
        doThrow(new IllegalStateException("index remove down")).when(index).remove("zke-t");
        doThrow(new IllegalStateException("index remove down")).when(index).remove("mke-t");

        when(rkeys.getType("gk")).thenThrow(new IllegalStateException("type down"));
        when(rkeys.getType("zk")).thenReturn(RType.ZSET);
        when(rkeys.getType("zke-t")).thenReturn(RType.ZSET);
        when(rkeys.getType("zke-s")).thenReturn(RType.ZSET);
        when(rkeys.getType("mk")).thenReturn(RType.MAP);
        when(rkeys.getType("mke-t")).thenReturn(RType.MAP);
        when(rkeys.getType("mke-s")).thenReturn(RType.MAP);
        when(rkeys.getType("bad")).thenReturn(RType.MAP);
        when(rkeys.getType("setk")).thenReturn(RType.SET);

        @SuppressWarnings("unchecked")
        RScoredSortedSet<String> zk = zsets.computeIfAbsent("zk", k -> mock(RScoredSortedSet.class));
        ScoredEntry<String> nullEntry = mock(ScoredEntry.class);
        ScoredEntry<String> nullValue = mock(ScoredEntry.class);
        when(nullValue.getValue()).thenReturn(null);
        ScoredEntry<String> ok = mock(ScoredEntry.class);
        when(ok.getValue()).thenReturn("m");
        when(ok.getScore()).thenReturn(1.5D);
        when(zk.entryRange(0, -1)).thenReturn(List.of(nullEntry, nullValue, ok));

        when(zsets.computeIfAbsent("zke-t", k -> mock(RScoredSortedSet.class)).entryRange(0, -1)).thenReturn(List.of());
        when(zsets.computeIfAbsent("zke-s", k -> mock(RScoredSortedSet.class)).entryRange(0, -1)).thenReturn(List.of());

        when(mapNamed("mk").readAllMap()).thenReturn(Map.of("f", "v"));
        when(mapNamed("mke-t").readAllMap()).thenReturn(Map.of());
        when(mapNamed("mke-s").readAllMap()).thenReturn(Map.of());
        when(mapNamed("bad").readAllMap()).thenThrow(new IllegalStateException("map down"));

        // gk has a thrown getType -> treated as map with data so it lands in the snapshot
        when(mapNamed("gk").readAllMap()).thenReturn(Map.of("f", "v"));

        Checkpoint cp = manager.triggerCheckpoint(List.of());
        assertNotNull(cp);
        @SuppressWarnings("unchecked")
        Map<String, Object> snap = cp.getStateSnapshot().getState("runtime:state");
        assertTrue(snap.containsKey("zk"));
        assertTrue(snap.containsKey("mk"));
        assertTrue(snap.containsKey("gk"));
    }

    @Test
    void snapshotStateSchemaCoversBlankKeysAndErrorArms() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        when(index.readAll()).thenReturn(new java.util.HashSet<>(Arrays.asList(" ", "k1", "k2", "k3")));
        RMap<String, String> schema = mapNamed(SCHEMA_KEY);
        when(schema.get("k1")).thenReturn("T|1");
        when(schema.get("k2")).thenThrow(new IllegalStateException("schema down"));
        when(schema.get("k3")).thenReturn("  ");

        Checkpoint cp = manager.triggerCheckpoint(List.of());
        assertNotNull(cp);
        @SuppressWarnings("unchecked")
        Map<String, String> snap = cp.getStateSnapshot().getState("runtime:stateSchema");
        assertTrue(snap.containsKey("k1"));
        assertFalse(snap.containsKey("k3"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoreOffsetsCoversMissingPipelinesBlankIdsAndEvalFailure() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        RedisRuntimeCheckpointManager.PipelineKey a =
                new RedisRuntimeCheckpointManager.PipelineKey("t-a", "g");
        RedisRuntimeCheckpointManager.PipelineKey b =
                new RedisRuntimeCheckpointManager.PipelineKey("t-b", "g");
        RedisRuntimeCheckpointManager.PipelineKey missing =
                new RedisRuntimeCheckpointManager.PipelineKey("t-missing", "g");
        when(mapNamed(StreamKeys.topicMeta("t-a")).get("partitionCount")).thenReturn("2");
        when(mapNamed(StreamKeys.topicMeta("t-b")).get("partitionCount")).thenReturn("1");
        when(mapNamed(StreamKeys.topicMeta("t-missing")).get("partitionCount")).thenReturn("1");
        when(script.eval(any(), anyString(), any(), any(List.class), any(), any()))
                .thenThrow(new IllegalStateException("lua down"));

        Map<String, Map<Integer, String>> offsets = new HashMap<>();
        Map<Integer, String> aOffsets = new HashMap<>();
        aOffsets.put(0, "5-9");
        aOffsets.put(1, " ");
        offsets.put(a.key(), aOffsets);
        offsets.put(b.key(), Map.of(0, "x"));

        DefaultCheckpoint cp = checkpoint(13L, null, null, null, offsets);
        assertTrue(manager.restoreFromCheckpoint(cp, List.of(a, b, missing)));
        verify(script, org.mockito.Mockito.atLeastOnce())
                .eval(any(), anyString(), any(), any(List.class), any(), any());
    }

    @Test
    void restoreStateCoversCleanupFailuresAndSkipsNullTypedValues() throws Exception {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        when(index.readAll()).thenReturn(new java.util.HashSet<>(Arrays.asList(null, " ", "e1")));
        doThrow(new IllegalStateException("delete down")).when(rkeys).delete("e1");
        doThrow(new IllegalStateException("index clear down")).when(index).clear();
        doThrow(new IllegalStateException("schema clear down")).when(mapNamed(SCHEMA_KEY)).clear();

        Map<String, Object> state = new HashMap<>();
        state.put(" ", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("a", "b")));
        state.put(PREFIX + ":null-v", null);
        state.put(PREFIX + ":null-typed", nullTypedStateValue());
        state.put(PREFIX + ":keep", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        Map<String, String> schemaSnap = new HashMap<>();
        schemaSnap.put(PREFIX + ":keep", "  ");
        DefaultCheckpoint cp = checkpoint(14L, null, state, schemaSnap, null);

        assertTrue(manager.restoreFromCheckpoint(cp, List.of()));
        verify(rkeys).delete("e1");
    }

    @Test
    void restoreStateCoversIndexReadFailure() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        when(index.readAll()).thenThrow(new IllegalStateException("readAll down"));
        Map<String, Object> state = new HashMap<>();
        state.put(PREFIX + ":keep", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        DefaultCheckpoint cp = checkpoint(15L, null, state, null, null);
        assertTrue(manager.restoreFromCheckpoint(cp, List.of()));
    }

    @Test
    void restoreStateCoversTtlExpireAndIndexWriteFailures() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ofSeconds(30), 5);
        doThrow(new IllegalStateException("index add down")).when(index).add(anyString());
        doThrow(new IllegalStateException("schema put down")).when(mapNamed(SCHEMA_KEY)).put(anyString(), anyString());

        RScoredSortedSet<String> zsetFails = zsets.computeIfAbsent(PREFIX + ":z-t", k -> mock(RScoredSortedSet.class));
        doThrow(new IllegalStateException("zset expire down")).when(zsetFails).expire(any(Duration.class));
        zsets.computeIfAbsent(PREFIX + ":z-s", k -> mock(RScoredSortedSet.class));
        RMap<String, String> mapFails = mapNamed(PREFIX + ":m-t");
        doThrow(new IllegalStateException("map expire down")).when(mapFails).expire(any(Duration.class));
        mapNamed(PREFIX + ":m-s");

        Map<String, Object> state = new HashMap<>();
        state.put(PREFIX + ":z-t", RedisRuntimeCheckpointManager.RedisStateValue.ofZset(Map.of("m", 1.0D)));
        state.put(PREFIX + ":z-s", RedisRuntimeCheckpointManager.RedisStateValue.ofZset(Map.of("m", 1.0D)));
        state.put(PREFIX + ":m-t", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        state.put(PREFIX + ":m-s", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        Map<String, String> schemaSnap = Map.of(
                PREFIX + ":z-t", "Z|1", PREFIX + ":z-s", "Z|1",
                PREFIX + ":m-t", "M|1", PREFIX + ":m-s", "M|1");
        DefaultCheckpoint cp = checkpoint(16L, null, state, schemaSnap, null);

        assertTrue(manager.restoreFromCheckpoint(cp, List.of()));
        verify(zsetFails).expire(any(Duration.class));
        verify(mapFails).expire(any(Duration.class));
    }

    @Test
    void restoreStateCoversKeyWriteFailure() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        RMap<String, String> map = mapNamed(PREFIX + ":m");
        doThrow(new IllegalStateException("putAll down")).when(map).putAll(any());
        Map<String, Object> state = new HashMap<>();
        state.put(PREFIX + ":m", RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        DefaultCheckpoint cp = checkpoint(17L, null, state, null, null);
        assertTrue(manager.restoreFromCheckpoint(cp, List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cleanupOldCoversDeleteAndMarkerFailures() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 1);
        DefaultCheckpoint newest = new DefaultCheckpoint(3L, 300L);
        DefaultCheckpoint middle = new DefaultCheckpoint(2L, 200L);
        DefaultCheckpoint oldest = new DefaultCheckpoint(1L, 100L);
        when(rkeys.getKeys()).thenReturn(List.of(STORAGE_PREFIX + "1", STORAGE_PREFIX + "2", STORAGE_PREFIX + "3"));
        RBucket<Object> b1 = buckets.computeIfAbsent(STORAGE_PREFIX + "1", k -> mock(RBucket.class));
        RBucket<Object> b2 = buckets.computeIfAbsent(STORAGE_PREFIX + "2", k -> mock(RBucket.class));
        RBucket<Object> b3 = buckets.computeIfAbsent(STORAGE_PREFIX + "3", k -> mock(RBucket.class));
        when(b1.get()).thenReturn(oldest);
        when(b2.get()).thenReturn(middle);
        when(b3.get()).thenReturn(newest);
        when(b2.delete()).thenThrow(new IllegalStateException("delete down"));
        RBucket<String> marker1 = markers.computeIfAbsent(manager.sinkCommittedMarkerKey(1L), k -> mock(RBucket.class));
        when(marker1.delete()).thenThrow(new IllegalStateException("marker delete down"));

        assertNotNull(manager.triggerCheckpoint(List.of()));
    }

    @Test
    void cleanupOldSwallowsListFailure() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 1);
        when(rkeys.getKeys()).thenThrow(new IllegalStateException("keys down"));
        assertNotNull(manager.triggerCheckpoint(List.of()));
    }

    @Test
    void restoreFromLatestReturnsNullWhenJobNameMismatches() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO, 5);
        DefaultCheckpoint foreign = checkpoint(18L, Map.of("jobName", "someone-else"), null, null, null);
        when(rkeys.getKeys()).thenReturn(List.of(STORAGE_PREFIX + "18"));
        RBucket<Object> bucket = buckets.computeIfAbsent(STORAGE_PREFIX + "18", k -> mock(RBucket.class));
        when(bucket.get()).thenReturn(foreign);
        assertNull(manager.restoreFromLatestCheckpointOrNull(List.of()));
        assertFalse(manager.restoreFromLatestCheckpoint(List.of()));
    }
}
