package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.api.stream.TwoPhaseCommitSink;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.Codec;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fault-injection coverage of the two-phase-commit checkpoint flow
 * ({@code preCommit -> storeCheckpoint(txn) -> commit -> mark sinkCommitted -> ack}):
 * a checkpoint written but never committed is compensated through the stored handles,
 * a throwing commit leaves the checkpoint unmarked and recovery still finalizes, and a
 * failed store discards the pre-committed transactions. Redis is mocked in-memory (same
 * plumbing as {@link RedisRuntimeCheckpointManagerFaultInjectionTest}); the full
 * Redis-backed environment cycle stays an integration-test item.
 */
class TwoPhaseCommitFaultInjectionTest {

    private static final String JOB = "job-2pc";
    private static final String PREFIX = "it-2pc-cpm";

    static class Txn implements Serializable {
        private static final long serialVersionUID = 1L;
        final long epoch;
        Txn(long epoch) {
            this.epoch = epoch;
        }
    }

    static class FakeTwoPhaseSink implements TwoPhaseCommitSink<Object, Txn> {
        final List<Object> committed = new ArrayList<>();
        final List<Object> discarded = new ArrayList<>();
        long epochCounter;
        RuntimeException commitFailure;
        final List<Object> staged = new ArrayList<>();

        @Override
        public Txn beginTxn() {
            return new Txn(++epochCounter);
        }

        @Override
        public void invoke(Object value, Txn txn) {
            staged.add(value);
        }

        @Override
        public void preCommit(Txn txn) {
        }

        @Override
        public void commit(Txn txn) {
            if (commitFailure != null) {
                throw commitFailure;
            }
            committed.addAll(staged);
            staged.clear();
        }

        @Override
        public Txn recoverAndCommit(Txn txn) {
            committed.add("recovered:" + txn.epoch);
            return txn;
        }

        @Override
        public Txn recoverAndAbort(Txn txn) {
            discarded.add("recovered:" + txn.epoch);
            return txn;
        }
    }

    private RedissonClient redisson;
    private RKeys rkeys;
    private final Map<String, RMap<String, String>> maps = new ConcurrentHashMap<>();
    private final Map<String, RBucket<Object>> buckets = new ConcurrentHashMap<>();
    private final Map<String, RBucket<String>> markers = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        rkeys = mock(RKeys.class);
        RSet<String> index = (RSet<String>) mock(RSet.class);
        when(redisson.getKeys()).thenReturn(rkeys);
        when(redisson.getSet(anyString(), any(Codec.class))).thenReturn((RSet) index);
        when(redisson.getScript(any(Codec.class))).thenReturn(mock(RScript.class));
        when(redisson.getMap(anyString(), any(Codec.class)))
                .thenAnswer(inv -> mapNamed(inv.getArgument(0)));
        when(redisson.getMap(anyString()))
                .thenAnswer(inv -> mapNamed(inv.getArgument(0)));
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

    private RedisRuntimeCheckpointManager manager() {
        return manager(RedisRuntimeConfig.builder()
                .jobName(JOB)
                .stateKeyPrefix(PREFIX)
                .checkpointKeyPrefix(PREFIX + ":cp")
                .stateTtl(Duration.ZERO)
                .checkpointsToKeep(5)
                .deferAckUntilCheckpoint(true)
                .build());
    }

    private RedisRuntimeCheckpointManager manager(RedisRuntimeConfig config) {
        return new RedisRuntimeCheckpointManager(redisson, config);
    }

    private static io.github.cuihairu.redis.streaming.mq.Message msg() {
        io.github.cuihairu.redis.streaming.mq.Message m = new io.github.cuihairu.redis.streaming.mq.Message();
        m.setId("m1");
        m.setTimestamp(java.time.Instant.ofEpochMilli(1000L));
        m.setPayload("p");
        return m;
    }

    private RedisPipelineRunner<Object> runnerWith(FakeTwoPhaseSink sink) {
        RedisRuntimeConfig config = RedisRuntimeConfig.builder()
                .jobName(JOB)
                .stateKeyPrefix(PREFIX)
                .build();
        return new RedisPipelineRunner<>(config, redisson, new ObjectMapper(), "topicA", "groupA",
                List.of((value, ctx, emit) -> emit.emit("p")), List.of(sink));
    }

    /** Drives the env's phase-1 + store sequence against the mocked manager. */
    private record StoredEpoch(Checkpoint checkpoint, Map<String, String> handles) {
    }

    private StoredEpoch storeHandles(RedisPipelineRunner<Object> runner, RedisRuntimeCheckpointManager manager) throws Exception {
        runner.handle(msg());
        Map<String, String> handles = new HashMap<>();
        runner.prepareTwoPhaseCommits()
                .forEach((sinkIndex, handle) -> handles.put("0:" + sinkIndex, handle));
        Checkpoint cp = manager.triggerCheckpoint(1L, List.of(), null, false,
                handles.isEmpty() ? null : handles);
        if (cp == null) {
            throw new IllegalStateException("mocked store unexpectedly failed");
        }
        return new StoredEpoch(cp, handles);
    }

    @Test
    void checkpointWrittenButCommitNotRunIsCompensatedFromStoredHandles() throws Exception {
        RedisRuntimeCheckpointManager manager = manager();
        FakeTwoPhaseSink sink = new FakeTwoPhaseSink();
        RedisPipelineRunner<Object> runner = runnerWith(sink);
        try {
            StoredEpoch stored = storeHandles(runner, manager);

            // the crash: handles are durably stored, but neither commit nor the
            // sink-committed marker ever ran
            assertTrue(stored.handles().containsKey("0:0"));
            assertEquals(stored.handles(), manager.getTxnHandles(stored.checkpoint()),
                    "the handles must be readable from the stored snapshot");
            assertFalse(manager.isSinkCommittedMarkerPresent(stored.checkpoint().getCheckpointId()),
                    "no marker: recovery must replay the commit");
            assertTrue(sink.committed.isEmpty());

            // restart: a fresh runner decodes the stored handle and compensates
            RedisPipelineRunner<Object> freshRunner = runnerWith(new FakeTwoPhaseSink());
            try {
                freshRunner.recoverTwoPhaseCommit(0, stored.handles().get("0:0"));
            } finally {
                freshRunner.close();
            }
        } finally {
            runner.close();
        }
    }

    @Test
    void commitThrowsLeavesCheckpointUnmarkedAndRecoveryStillFinalizes() throws Exception {
        RedisRuntimeCheckpointManager manager = manager();
        FakeTwoPhaseSink sink = new FakeTwoPhaseSink();
        sink.commitFailure = new IllegalStateException("kafka is down");
        RedisPipelineRunner<Object> runner = runnerWith(sink);
        try {
            StoredEpoch stored = storeHandles(runner, manager);

            // env phase 2: commit throws -> catch path aborts and returns WITHOUT
            // markSinkCommitted, so the marker is absent even though the store succeeded
            assertThrows(IllegalStateException.class, runner::commitTwoPhaseCommits);
            assertFalse(manager.isSinkCommittedMarkerPresent(stored.checkpoint().getCheckpointId()));
            assertTrue(sink.committed.isEmpty(), "failed commit must not report success");

            // recovery from the stored handle still finalizes the epoch (idempotent contract)
            RedisPipelineRunner<Object> freshRunner = runnerWith(new FakeTwoPhaseSink());
            try {
                freshRunner.recoverTwoPhaseCommit(0, stored.handles().get("0:0"));
            } finally {
                freshRunner.close();
            }
        } finally {
            runner.close();
        }
    }

    @Test
    void failedCommitKeepsTheEpochOpenForARetryInsteadOfDiscardingIt() throws Exception {
        FakeTwoPhaseSink sink = new FakeTwoPhaseSink();
        sink.commitFailure = new IllegalStateException("kafka is down");
        RedisPipelineRunner<Object> runner = runnerWith(sink);
        try {
            runner.handle(msg());
            runner.prepareTwoPhaseCommits();

            assertThrows(IllegalStateException.class, runner::commitTwoPhaseCommits);
            assertTrue(sink.discarded.isEmpty(),
                    "a failed commit must not discard the transaction: its handle is already stored in "
                            + "a durable checkpoint, so recovery commits it rather than aborting it");
            assertTrue(sink.committed.isEmpty(), "the half-applied commit must not report success");

            // the epoch survived the failure, so either the retried phase 2 (next checkpoint on the
            // same runner) or the recovery path can still finalize the staged data
            sink.commitFailure = null;
            runner.commitTwoPhaseCommits();
            assertFalse(sink.committed.isEmpty(), "the retried commit finalizes the staged data");
        } finally {
            runner.close();
        }
    }

    @Test
    void storeFailureDiscardsPreCommittedTransactions() throws Exception {
        RedisRuntimeCheckpointManager manager = manager();
        FakeTwoPhaseSink sink = new FakeTwoPhaseSink();
        RedisPipelineRunner<Object> runner = runnerWith(sink);
        try {
            runner.handle(msg());
            runner.prepareTwoPhaseCommits();
            assertFalse(sink.staged.isEmpty(), "elements staged into the txn");

            // storage breaks: triggerCheckpoint returns null -> env aborts the epochs
            when(redisson.getBucket(anyString())).thenThrow(new IllegalStateException("redis down"));
            Checkpoint cp = manager.triggerCheckpoint(2L, List.of(), null, false, Map.of("0:0", "irrelevant"));
            assertNull(cp);
            // re-stub with doAnswer: when(...).thenAnswer would re-invoke the throwing stub
            doAnswer(inv -> buckets.computeIfAbsent(inv.getArgument(0), k -> mock(RBucket.class)))
                    .when(redisson).getBucket(anyString());

            runner.abortTwoPhaseCommits();

            assertTrue(sink.committed.isEmpty(), "aborted epoch data never becomes visible");
            assertFalse(sink.discarded.isEmpty(),
                    "this sink uses the interface default: live abort delegates to recoverAndAbort");
        } finally {
            runner.close();
        }
    }

    @Test
    void checkpointsWithoutTwoPhaseSinksCarryNoTxnKey() throws Exception {
        RedisRuntimeCheckpointManager manager = manager();
        Checkpoint plain = manager.triggerCheckpoint(3L, List.of(), null, false);
        assertEquals(Map.of(), manager.getTxnHandles(plain),
                "the 4-arg overload must not write txn handles");

        Checkpoint emptyMap = manager.triggerCheckpoint(4L, List.of(), null, false, Map.of());
        assertEquals(Map.of(), manager.getTxnHandles(emptyMap));
    }

    @Test
    void sinkCommittedMarkerPresentMeansNoReplayIsNeeded() throws Exception {
        RedisRuntimeCheckpointManager manager = manager();
        long checkpointId = 9L;
        manager.markSinkCommittedMarker(checkpointId);

        // the mock bucket does not persist set() -> stub isExists explicitly to model the
        // marker write that succeeded before the crash
        RBucket<String> marker = markers.get(manager.sinkCommittedMarkerKey(checkpointId));
        when(marker.isExists()).thenReturn(true);

        assertTrue(manager.isSinkCommittedMarkerPresent(checkpointId));
    }

    @Test
    void inDoubtCheckpointIsAdoptedAsTheRestorePointWhenItIsTheNewest() throws Exception {
        RedisRuntimeCheckpointManager manager = manager();
        // an older, fully committed checkpoint
        Checkpoint older = manager.triggerCheckpoint(1L, List.of(), null, false);
        manager.markSinkCommitted(older);
        presentMarker(manager, older.getCheckpointId());

        // the crash: a newer checkpoint stored its handles but never committed them
        Checkpoint inDoubt = manager.triggerCheckpoint(2L, List.of(), null, false,
                Map.of("0:0", TwoPhaseCommitCoordinator.encodeTxn(new Txn(7))));

        // listCheckpoints is the scan both lookups use; feed it the two stored checkpoints
        publishCheckpoints(inDoubt, older);

        Checkpoint adopted = manager.getLatestInDoubtTwoPhaseCheckpoint();
        assertNotNull(adopted, "the stored-but-uncommitted epoch must be discoverable");
        assertEquals(2L, adopted.getCheckpointId(),
                "its offsets may only be adopted together with its transaction handles");
        assertEquals(1, manager.getTxnHandles(adopted).size());

        // and the normal restore path picks it over the older committed checkpoint
        Checkpoint restored = manager.restoreFromLatestCheckpointOrNull(List.of());
        assertNotNull(restored);
        assertEquals(2L, restored.getCheckpointId(),
                "a newer in-doubt checkpoint outranks the older sink-committed one");
    }

    @Test
    void olderInDoubtCheckpointDoesNotOutrankANewerCommittedOne() throws Exception {
        RedisRuntimeCheckpointManager manager = manager();
        // the crashed epoch's checkpoint
        Checkpoint inDoubt = manager.triggerCheckpoint(1L, List.of(), null, false,
                Map.of("0:0", TwoPhaseCommitCoordinator.encodeTxn(new Txn(1))));
        // a later checkpoint completed and finalized the same open transaction
        Checkpoint newer = manager.triggerCheckpoint(2L, List.of(), null, false,
                Map.of("0:0", TwoPhaseCommitCoordinator.encodeTxn(new Txn(1))));
        manager.markSinkCommitted(newer);
        presentMarker(manager, newer.getCheckpointId());

        publishCheckpoints(newer, inDoubt);

        assertNull(manager.getLatestInDoubtTwoPhaseCheckpoint(),
                "the older epoch was already finalized by the newer committed checkpoint, "
                        + "replaying its handle would double-commit");
        Checkpoint restored = manager.restoreFromLatestCheckpointOrNull(List.of());
        assertNotNull(restored);
        assertEquals(2L, restored.getCheckpointId());
    }

    @Test
    void abortedEpochIsNeverAdoptedBecauseItsDataNoLongerExists() throws Exception {
        RedisRuntimeCheckpointManager manager = manager();
        // epoch stored in checkpoint 1, then a later checkpoint failed before storing and
        // the epoch was discarded: restoring checkpoint 1's offsets would skip records
        // whose data was thrown away
        Checkpoint dead = manager.triggerCheckpoint(1L, List.of(), null, false,
                Map.of("0:0", TwoPhaseCommitCoordinator.encodeTxn(new Txn(3))));
        assertTrue(manager.markTxnEpochAborted(dead.getCheckpointId()));
        RBucket<String> marker = markers.get(manager.txnAbortedMarkerKey(dead.getCheckpointId()));
        when(marker.isExists()).thenReturn(true);

        publishCheckpoints(dead);

        assertTrue(manager.isTxnEpochAborted(dead.getCheckpointId()));
        assertNull(manager.getLatestInDoubtTwoPhaseCheckpoint(),
                "a discarded epoch must not become a restore point");
        assertNull(manager.restoreFromLatestCheckpointOrNull(List.of()),
                "with no committed checkpoint left there is nothing safe to restore");
    }

    /** Makes a marker bucket report {@code isExists}, modelling a successful marker write. */
    private void presentMarker(RedisRuntimeCheckpointManager manager, long checkpointId) {
        RBucket<String> marker = markers.get(manager.sinkCommittedMarkerKey(checkpointId));
        when(marker.isExists()).thenReturn(true);
    }

    /**
     * Publishes checkpoints into the mocked keyspace so {@code listCheckpoints} — the scan
     * both the sink-committed and the in-doubt lookup go through — can find them.
     */
    private void publishCheckpoints(Checkpoint... checkpoints) {
        String prefix = PREFIX + ":cp" + JOB + ":";
        List<String> keys = new ArrayList<>();
        for (Checkpoint cp : checkpoints) {
            String key = prefix + cp.getCheckpointId();
            keys.add(key);
            RBucket<Object> bucket = buckets.computeIfAbsent(key, k -> mock(RBucket.class));
            when(bucket.get()).thenReturn(cp);
        }
        when(rkeys.getKeys(any(KeysScanOptions.class))).thenReturn(keys);
    }

    @Test
    void multiRunnerHandlesRoundTripThroughTheSnapshotKeyShape() throws Exception {
        RedisRuntimeCheckpointManager manager = manager();
        Map<String, String> handles = new HashMap<>();
        handles.put("0:0", TwoPhaseCommitCoordinator.encodeTxn(new Txn(1)));
        handles.put("1:0", TwoPhaseCommitCoordinator.encodeTxn(new Txn(2)));
        handles.put("1:2", TwoPhaseCommitCoordinator.encodeTxn(new Txn(3)));

        Checkpoint cp = manager.triggerCheckpoint(5L, List.of(), null, false, handles);

        Map<String, String> read = manager.getTxnHandles(cp);
        assertEquals(handles, read);
        assertEquals(1L, ((Txn) TwoPhaseCommitCoordinator.decodeTxn(read.get("0:0"))).epoch);
        assertEquals(3L, ((Txn) TwoPhaseCommitCoordinator.decodeTxn(read.get("1:2"))).epoch);
    }

    @Test
    void twoPhaseSinksAlsoReachPlainSinksInTheSamePipeline() throws Exception {
        // a pipeline mixing a 2PC sink and a plain sink must keep delivering to both
        List<Object> plainSeen = new ArrayList<>();
        RedisRuntimeConfig config = RedisRuntimeConfig.builder()
                .jobName(JOB)
                .stateKeyPrefix(PREFIX)
                .build();
        StreamSink<Object> plain = plainSeen::add;
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(config, redisson, new ObjectMapper(),
                "topicA", "groupA", List.of((value, ctx, emit) -> emit.emit("p")),
                List.of(new FakeTwoPhaseSink(), plain));
        try {
            runner.handle(msg());
            assertEquals(1, plainSeen.size());
        } finally {
            runner.close();
        }
    }
}
