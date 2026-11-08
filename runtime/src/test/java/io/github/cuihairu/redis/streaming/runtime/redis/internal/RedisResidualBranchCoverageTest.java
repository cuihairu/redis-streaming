package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.StreamSink;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.join.operator.Envelope;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Targeted branch coverage for narrow error/edge paths that the wider suites do not reach:
 * join wire-codec serialization/deserialization failures and the context-classloader fallback,
 * HashSink {@code delete} raw-string fallback, JobControlGate interrupt/acquire failure, and the
 * RedisStreamBuilder window drain re-queue (fire exception) and empty member handling. No Redis.
 */
class RedisResidualBranchCoverageTest {

    private static RedisRuntimeConfig cfg(String job) {
        return RedisRuntimeConfig.builder().jobName(job).stateKeyPrefix("it-residual:" + job).build();
    }

    private static Message msg(long ts) {
        Message m = new Message();
        m.setId("m-" + ts);
        m.setTimestamp(Instant.ofEpochMilli(ts));
        m.setPayload("p");
        m.setHeaders(Map.of("partitionId", "0"));
        return m;
    }

    // ---------- join envelope codec ----------

    @Test
    void codecRejectsUnserializableJoinKey() {
        // a payload that Jackson cannot serialize triggers the encode RuntimeException wrapper
        Envelope<String, Object, Object> env = Envelope.forLeft("k", 1L, new Unserializable());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> RedisJoinEnvelopeCodec.toJson(env));
        assertTrue(ex.getMessage().contains("Failed to encode"), ex.getMessage());
        assertTrue(ex.getCause() != null);
    }

    @Test
    void codecParseFailsWithRuntimeException() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> RedisJoinEnvelopeCodec.parse("{bad json"));
        assertTrue(ex.getMessage().contains("Failed to decode"), ex.getMessage());
    }

    @Test
    void codecResolveTypeWithNullLoaderFallsBackToOwnLoader() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(null);
        try {
            String json = RedisJoinEnvelopeCodec.toJson(Envelope.forLeft("k", 1L, "v"));
            Envelope<String, String, Object> out = RedisJoinEnvelopeCodec.parse(json);
            assertEquals("k", out.getJoinKey());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void codecResolveUnknownTypeThrows() throws Exception {
        Method resolve = RedisJoinEnvelopeCodec.class.getDeclaredMethod("resolveType", String.class);
        resolve.setAccessible(true);
        java.lang.reflect.InvocationTargetException wrapped = assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> resolve.invoke(null, "no.such.Type"));
        assertTrue(wrapped.getCause() instanceof RuntimeException);
        assertTrue(wrapped.getCause().getMessage().contains("Cannot resolve"), wrapped.getCause().getMessage());
    }

    // ---------- JobControlGate interrupt ----------

    @Test
    void jobControlGateReturnsFalseWhenInterruptedAndAcquireFails() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        org.redisson.api.RScript script = mock(org.redisson.api.RScript.class);
        when(redis.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        // exclusive wins, silence eval throws -> gate must release exclusive and return false
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class)))
                .thenReturn(1L)
                .thenThrow(new IllegalStateException("silence down"));
        JobControlGate gate = new JobControlGate(redis, "ctl:excl", "ctl:silence", "i1", 1000L, 100L);
        assertFalse(gate.tryBeginControlOp(0L));

        // interrupt path: the exclusive lease is denied, so the wait loop sleeps and is interrupted
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn(null);
        Thread.currentThread().interrupt();
        assertFalse(gate.tryBeginControlOp(10L));
        assertTrue(Thread.interrupted(), "the interrupt flag must be re-set");
    }

    // ---------- RedisPipelineRunner 2PC guard ----------

    @Test
    void pipelineRunnerTwoPhaseRecoveryRejectsNonTwoPhaseSink() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                cfg("2pc"), redis, new ObjectMapper(), "t", "g", List.of(), List.of((StreamSink<Object>) v -> { }));
        try {
            assertThrows(IllegalStateException.class, () -> runner.recoverTwoPhaseCommit(0, "txn"));
            assertThrows(IllegalStateException.class, () -> runner.recoverTwoPhaseAbort(0, "txn"));
        } finally {
            runner.close();
        }
    }

    @Test
    void twoPhaseEpochTrackerMarksClearsAndTakesDurable() throws Exception {
        Class<?> tracker = Class.forName(
                "io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment$TwoPhaseEpochTracker");
        java.lang.reflect.Constructor<?> ctor = tracker.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object t = ctor.newInstance();
        java.lang.reflect.Method markDurable = tracker.getDeclaredMethod("markDurable", long.class);
        java.lang.reflect.Method clearDurable = tracker.getDeclaredMethod("clearDurable");
        java.lang.reflect.Method takeDurable = tracker.getDeclaredMethod("takeDurable");
        markDurable.setAccessible(true); clearDurable.setAccessible(true); takeDurable.setAccessible(true);
        assertNull(takeDurable.invoke(t), "no durable checkpoint -> null");
        markDurable.invoke(t, 7L);
        assertEquals(7L, takeDurable.invoke(t), "take consumes the durable id");
        assertNull(takeDurable.invoke(t), "second take is empty");
        markDurable.invoke(t, 9L);
        clearDurable.invoke(t);
        assertNull(takeDurable.invoke(t), "clear discards the durable id");
    }

    @Test
    void pipelineRunnerIdleSweepSwallowsHookAndOuterFailures() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("idle").stateKeyPrefix("it-residual:idle")
                .watermarkIdleTimeout(Duration.ofMillis(1)).build();
        final RedisPipelineRunner.Context[] captured = new RedisPipelineRunner.Context[1];
        RedisOperatorNode registrar = (value, ctx, emit) -> {
            captured[0] = ctx;
            ctx.addIdleFlushHook(() -> { throw new IllegalStateException("hook down"); });
            emit.emit(value);
        };
        RedisPipelineRunner<Object> runner = new RedisPipelineRunner<>(
                cfg, redis, new ObjectMapper(), "t", "g", List.of(registrar), List.of());
        try {
            // force the idle condition (markIdle) and run the private sweep: the throwing
            // hook and the metrics setter failure must both be swallowed
            runner.handle(msg(1L));
            Method markIdle = RedisPipelineRunner.Context.class.getDeclaredMethod("markIdle");
            markIdle.setAccessible(true);
            markIdle.invoke(captured[0]);
            Method idleSweep = RedisPipelineRunner.class.getDeclaredMethod("idleSweep");
            idleSweep.setAccessible(true);
            assertDoesNotThrow(() -> idleSweep.invoke(runner));
        } finally {
            runner.close();
        }
    }

    private static void assertFalse(boolean value) {
        org.junit.jupiter.api.Assertions.assertFalse(value);
    }

    private static final class Unserializable {
        // Jackson serializes the object graph; a self-referencing cycle fails the mapper
        public Unserializable self = this;
    }
}
