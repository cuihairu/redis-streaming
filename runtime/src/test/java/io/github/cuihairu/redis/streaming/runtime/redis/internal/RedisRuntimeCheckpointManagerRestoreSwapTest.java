package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.api.options.KeysScanOptions;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RT-M4: state restore is now stage-then-rename. The staging and swap phases fail-stop
 * (the live state is never deleted up front), a snapshot entry with empty data clears the
 * live key, and a requested-but-failed restore throws instead of degrading into a
 * stateless start.
 */
class RedisRuntimeCheckpointManagerRestoreSwapTest {

    private static final String JOB = "job-rst";
    private static final String PREFIX = "it-rst-cpm";
    private static final String STORAGE_PREFIX = PREFIX + ":cp" + JOB + ":";

    private RedissonClient redisson;
    private RKeys rkeys;
    private RSet<String> index;
    private RScript script;
    private final Map<String, RMap<String, String>> maps = new ConcurrentHashMap<>();
    private final Map<String, RBucket<Object>> buckets = new ConcurrentHashMap<>();

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
        when(redisson.getBucket(anyString()))
                .thenAnswer(inv -> buckets.computeIfAbsent(inv.getArgument(0), k -> mock(RBucket.class)));
        when(index.readAll()).thenReturn(new java.util.HashSet<>());
    }

    private RMap<String, String> mapNamed(String name) {
        return maps.computeIfAbsent(name, k -> mock(RMap.class));
    }

    private RedisRuntimeCheckpointManager manager(Duration stateTtl) {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName(JOB)
                .stateKeyPrefix(PREFIX)
                .checkpointKeyPrefix(PREFIX + ":cp")
                .stateTtl(stateTtl)
                .checkpointsToKeep(5)
                .build();
        return new RedisRuntimeCheckpointManager(redisson, cfg);
    }

    private static DefaultCheckpoint checkpoint(long id, Map<String, Object> state) {
        DefaultCheckpoint cp = new DefaultCheckpoint(id, System.currentTimeMillis());
        cp.getStateSnapshot().putState("runtime:meta", Map.of("jobName", JOB));
        if (state != null) {
            cp.getStateSnapshot().putState("runtime:state", state);
        }
        return cp;
    }

    @Test
    void stagingFailureLeavesLiveStateUntouchedAndFailsStop() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO);
        String k1 = PREFIX + ":st:k1";
        when(index.readAll()).thenReturn(new java.util.HashSet<>(List.of(k1)));
        Map<String, Object> state = new HashMap<>();
        state.put(k1, RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        DefaultCheckpoint cp = checkpoint(31L, state);
        doThrow(new IllegalStateException("staging down"))
                .when(mapNamed(k1 + ":rst:31")).putAll(any());

        assertFalse(manager.restoreFromCheckpoint(cp, List.of()));
        // the live key was never deleted nor renamed: a staging failure must not touch it
        verify(rkeys, never()).delete(k1);
        verify(rkeys, never()).rename(anyString(), anyString());
        // the staged data key is cleaned up; index/schema staging was not reached yet
        verify(rkeys).delete(k1 + ":rst:31");
    }

    @Test
    void swapFailureFailsStopAndCleansStagingKeys() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO);
        String k1 = PREFIX + ":st:k1";
        Map<String, Object> state = new HashMap<>();
        state.put(k1, RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        DefaultCheckpoint cp = checkpoint(32L, state);
        doThrow(new IllegalStateException("rename failed")).when(rkeys).rename(anyString(), anyString());

        assertFalse(manager.restoreFromCheckpoint(cp, List.of()));
        verify(rkeys, never()).delete(k1);
        // each staging key is deleted pre-stage (idempotent re-staging) and again by the
        // swap-failure cleanup
        verify(rkeys, atLeastOnce()).delete(k1 + ":rst:32");
        verify(rkeys, atLeastOnce()).delete(PREFIX + ":" + JOB + ":stateKeys:rst:32");
        verify(rkeys, atLeastOnce()).delete(PREFIX + ":" + JOB + ":stateSchema:rst:32");
    }

    @Test
    void emptySnapshotEntryClearsTheLiveKeyWithoutStaging() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO);
        String k1 = PREFIX + ":st:k1";
        when(index.readAll()).thenReturn(new java.util.HashSet<>(List.of(k1)));
        Map<String, Object> state = new HashMap<>();
        state.put(k1, RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of()));
        DefaultCheckpoint cp = checkpoint(33L, state);

        assertTrue(manager.restoreFromCheckpoint(cp, List.of()));
        // "restore to empty": the live key is removed, nothing was staged for it; the
        // snapshot holds no index/schema entries, so those live keys are dropped too —
        // RENAME of an absent staging key would error on real Redis
        verify(rkeys).delete(k1);
        verify(mapNamed(k1), never()).putAll(any());
        verify(rkeys, never()).rename(anyString(), anyString());
        verify(rkeys).delete(PREFIX + ":" + JOB + ":stateKeys");
        verify(rkeys).delete(PREFIX + ":" + JOB + ":stateSchema");
    }

    @Test
    void stateTtlIsAppliedToRestoredKeys() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ofSeconds(60));
        String k1 = PREFIX + ":st:k1";
        Map<String, Object> state = new HashMap<>();
        state.put(k1, RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        DefaultCheckpoint cp = checkpoint(34L, state);

        assertTrue(manager.restoreFromCheckpoint(cp, List.of()));
        verify(mapNamed(k1)).expire(Duration.ofSeconds(60));
    }

    @Test
    void requestedRestoreFailureThrowsInsteadOfReturningNull() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO);
        String k1 = PREFIX + ":st:k1";
        Map<String, Object> state = new HashMap<>();
        state.put(k1, RedisRuntimeCheckpointManager.RedisStateValue.ofMap(Map.of("f", "v")));
        DefaultCheckpoint cp = checkpoint(35L, state);
        when(rkeys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of(STORAGE_PREFIX + "35"));
        @SuppressWarnings("unchecked")
        RBucket<Object> storage35 = (RBucket<Object>) buckets.computeIfAbsent(STORAGE_PREFIX + "35", k -> mock(RBucket.class));
        when(storage35.get()).thenReturn(cp);
        doThrow(new IllegalStateException("staging down"))
                .when(mapNamed(k1 + ":rst:35")).putAll(any());

        assertThrows(IllegalStateException.class,
                () -> manager.restoreFromLatestCheckpointOrNull(List.of()));
    }

    @Test
    void noCheckpointYetStillReturnsNullForFreshStart() {
        RedisRuntimeCheckpointManager manager = manager(Duration.ZERO);
        when(rkeys.getKeys(any(KeysScanOptions.class))).thenReturn(List.of());

        assertNull(assertDoesNotThrow(() -> manager.restoreFromLatestCheckpointOrNull(List.of())));
    }
}
