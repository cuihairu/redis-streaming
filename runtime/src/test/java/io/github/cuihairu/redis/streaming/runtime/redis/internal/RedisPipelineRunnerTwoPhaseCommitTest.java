package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.api.stream.TwoPhaseCommitSink;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Runner-level two-phase-commit integration (unit, no Redis): {@link RedisPipelineRunner}
 * must route elements of a {@link TwoPhaseCommitSink} through its
 * {@link TwoPhaseCommitCoordinator}, expose the prepare/commit/abort phases with
 * sink-index-keyed encoded handles, and reject recovery targeting a plain sink.
 */
class RedisPipelineRunnerTwoPhaseCommitTest {

    static class RecordingTxn implements Serializable {
        private static final long serialVersionUID = 1L;
        final long epoch;
        RecordingTxn(long epoch) {
            this.epoch = epoch;
        }
    }

    /** Visible-only-after-commit fake, same semantics the runtime contract guarantees. */
    static class RecordingTwoPhaseSink implements TwoPhaseCommitSink<Object, RecordingTxn> {
        final List<Object> committed = new ArrayList<>();
        final List<Object> discarded = new ArrayList<>();
        final List<RecordingTxn> invokedTxns = new ArrayList<>();
        long epochCounter;
        RecordingTxn openTxn;
        final List<Object> staged = new ArrayList<>();

        @Override
        public RecordingTxn beginTxn() {
            return new RecordingTxn(++epochCounter);
        }

        @Override
        public void invoke(Object value, RecordingTxn txn) {
            invokedTxns.add(txn);
            if (openTxn == null) {
                openTxn = txn;
            }
            staged.add(value);
        }

        @Override
        public void preCommit(RecordingTxn txn) {
        }

        @Override
        public void commit(RecordingTxn txn) {
            committed.addAll(staged);
            staged.clear();
            openTxn = null;
        }

        @Override
        public RecordingTxn recoverAndCommit(RecordingTxn txn) {
            committed.add("recovered:" + txn.epoch);
            return txn;
        }

        @Override
        public RecordingTxn recoverAndAbort(RecordingTxn txn) {
            discarded.add("recovered:" + txn.epoch);
            return txn;
        }
    }

    /** Maps the raw Message to a stable value so committed data assertions are readable. */
    private static final RedisOperatorNode MAP_TO_P = (value, ctx, emit) -> emit.emit("p");

    private static RedissonClient redis() {
        return mock(RedissonClient.class);
    }

    private static RedisRuntimeConfig config() {
        return RedisRuntimeConfig.builder()
                .jobName("2pc")
                .stateKeyPrefix("it-2pc-unit")
                .build();
    }

    private static Message msg(String id) {
        Message m = new Message();
        m.setId(id);
        m.setTimestamp(Instant.ofEpochMilli(1000L));
        m.setPayload("p");
        return m;
    }

    @Test
    void elementsOfTwoPhaseSinksAreRoutedThroughTheOpenTransaction() throws Exception {
        RecordingTwoPhaseSink twoPhase = new RecordingTwoPhaseSink();
        List<Object> plainSeen = new ArrayList<>();
        StreamSink<Object> plain = plainSeen::add;

        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config(), redis(), new ObjectMapper(), "topicA", "groupA",
                List.of(MAP_TO_P), List.of(twoPhase, plain));
        try {
            assertTrue(runner.hasTwoPhaseCommitSinks());
            runner.handle(msg("m1"));
            runner.handle(msg("m2"));

            assertEquals(2, twoPhase.invokedTxns.size());
            assertSame(twoPhase.invokedTxns.get(0), twoPhase.invokedTxns.get(1),
                    "one epoch, one transaction for both elements");
            assertTrue(twoPhase.committed.isEmpty(),
                    "no commit without the runtime-driven phases");
            assertEquals(List.of("p", "p"), plainSeen,
                    "plain sinks keep the direct invoke path");
        } finally {
            runner.close();
        }
    }

    @Test
    void prepareReturnsHandlesOnlyForTwoPhaseSinksAndCommitFinalizes() throws Exception {
        RecordingTwoPhaseSink twoPhase = new RecordingTwoPhaseSink();
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config(), redis(), new ObjectMapper(), "topicA", "groupA",
                List.of(MAP_TO_P), List.of(twoPhase));
        try {
            runner.handle(msg("m1"));

            var handles = runner.prepareTwoPhaseCommits();
            assertEquals(1, handles.size(), "only the 2PC sink gets a handle");
            assertTrue(handles.containsKey(0));

            assertTrue(twoPhase.committed.isEmpty());
            runner.commitTwoPhaseCommits();
            assertEquals(List.of("p"), twoPhase.committed,
                    "commit after checkpoint store makes staged data visible");
        } finally {
            runner.close();
        }
    }

    @Test
    void abortDiscardsStagedDataOnCheckpointFailure() throws Exception {
        RecordingTwoPhaseSink twoPhase = new RecordingTwoPhaseSink();
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config(), redis(), new ObjectMapper(), "topicA", "groupA",
                List.of(MAP_TO_P), List.of(twoPhase));
        try {
            runner.handle(msg("m1"));
            runner.prepareTwoPhaseCommits();

            runner.abortTwoPhaseCommits();

            assertTrue(twoPhase.committed.isEmpty(), "aborted data never becomes visible");
            assertFalse(runner.prepareTwoPhaseCommits().isEmpty(),
                    "next epoch prepares a fresh transaction");
            runner.commitTwoPhaseCommits();
            assertEquals(List.of("p"), twoPhase.committed,
                    "the fresh epoch commits normally");
        } finally {
            runner.close();
        }
    }

    @Test
    void runnerWithoutTwoPhaseSinksPreparesNothing() throws Exception {
        List<Object> seen = new ArrayList<>();
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config(), redis(), new ObjectMapper(), "topicA", "groupA",
                List.of(MAP_TO_P), List.of(seen::add));
        try {
            assertFalse(runner.hasTwoPhaseCommitSinks());
            runner.handle(msg("m1"));
            assertTrue(runner.prepareTwoPhaseCommits().isEmpty());
            assertDoesNotThrowCommitAndAbort(runner);
            assertEquals(List.of("p"), seen);
        } finally {
            runner.close();
        }
    }

    private static void assertDoesNotThrowCommitAndAbort(RedisPipelineRunner<Object> runner) throws Exception {
        runner.commitTwoPhaseCommits();
        runner.abortTwoPhaseCommits();
    }

    @Test
    void recoveryTargetsOnlyTwoPhaseSinks() throws Exception {
        RecordingTwoPhaseSink twoPhase = new RecordingTwoPhaseSink();
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                config(), redis(), new ObjectMapper(), "topicA", "groupA",
                List.of(MAP_TO_P), List.of(twoPhase, (StreamSink<Object>) v -> {
                }));
        try {
            String handle = TwoPhaseCommitCoordinator.encodeTxn(new RecordingTxn(7));

            runner.recoverTwoPhaseCommit(0, handle);
            assertEquals(List.of("recovered:7"), twoPhase.committed);

            runner.recoverTwoPhaseAbort(0, handle);
            assertEquals(List.of("recovered:7"), twoPhase.discarded);

            assertThrows(IllegalStateException.class,
                    () -> runner.recoverTwoPhaseCommit(1, handle),
                    "recovery on a plain sink index must fail loudly");
        } finally {
            runner.close();
        }
    }
}
