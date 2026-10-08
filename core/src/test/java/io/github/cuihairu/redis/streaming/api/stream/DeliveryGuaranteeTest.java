package io.github.cuihairu.redis.streaming.api.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the {@link DeliveryGuarantee} declaration surface: plain sinks default to
 * AT_LEAST_ONCE, checkpoint-aware hooks do not strengthen it on their own, and two-phase-commit
 * sinks declare EFFECTIVELY_ONCE by default.
 */
class DeliveryGuaranteeTest {

    @Test
    void enumDefinesThreeLevels() {
        assertEquals(3, DeliveryGuarantee.values().length);
        assertNotNull(DeliveryGuarantee.valueOf("AT_MOST_ONCE"));
        assertNotNull(DeliveryGuarantee.valueOf("AT_LEAST_ONCE"));
        assertNotNull(DeliveryGuarantee.valueOf("EFFECTIVELY_ONCE"));
    }

    @Test
    void plainLambdaSinkDefaultsToAtLeastOnce() {
        StreamSink<String> sink = value -> { };
        assertEquals(DeliveryGuarantee.AT_LEAST_ONCE, sink.deliveryGuarantee());
    }

    @Test
    void checkpointAwareSinkDefaultsToAtLeastOnce() {
        CheckpointAwareSink<String> sink = new CheckpointAwareSink<>() {
            @Override
            public void invoke(String value) {
            }
        };
        assertEquals(DeliveryGuarantee.AT_LEAST_ONCE, sink.deliveryGuarantee());
    }

    @Test
    void twoPhaseCommitSinkDefaultsToEffectivelyOnce() {
        TwoPhaseCommitSink<String, String> sink = new TwoPhaseCommitSink<>() {
            @Override
            public String beginTxn() {
                return "txn";
            }

            @Override
            public void invoke(String value, String txn) {
            }

            @Override
            public void preCommit(String txn) {
            }

            @Override
            public void commit(String txn) {
            }

            @Override
            public void abort(String txn) {
            }

            @Override
            public String recoverAndCommit(String txn) {
                return txn;
            }

            @Override
            public String recoverAndAbort(String txn) {
                return txn;
            }
        };
        assertEquals(DeliveryGuarantee.EFFECTIVELY_ONCE, sink.deliveryGuarantee());
    }
}
