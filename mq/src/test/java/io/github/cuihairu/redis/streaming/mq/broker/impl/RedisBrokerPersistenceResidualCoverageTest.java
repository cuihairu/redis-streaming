package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for RedisBrokerPersistence.append: JSON fallback when serialization
 * fails, successful atomic Lua XADD, fallback add returning null, and the exact/approximate
 * trim ladder including per-entry removal failures.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisBrokerPersistenceResidualCoverageTest {

    /** Payload bean whose getter blows up so Jackson serialization fails. */
    public static class HostilePayload {
        public String getValue() {
            throw new IllegalStateException("getter boom");
        }
    }

    private static Message msg(Object payload) {
        Message m = new Message();
        m.setTopic("t");
        m.setPayload(payload);
        return m;
    }

    @Test
    void appendFallsBackToStringWhenJsonSerializationFails() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(client.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn("11-0");
        when(stream.add(any())).thenReturn(new StreamMessageId(11, 0));

        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(0).build());
        assertEquals("11-0", persistence.append("t", 0, msg(new HostilePayload())));
    }

    @Test
    void appendAtomicXAddSuccessReturnsLuaId() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(client.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn("12-0");

        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(5).build());
        assertEquals("12-0", persistence.append("t", 0, msg("p")));
    }

    @Test
    void appendAtomicXAddNullResultAndRecordingFailure() {
        io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetricsCollector prev =
                io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get();
        io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.setCollector(
                new io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetricsCollector() {
                    @Override public void recordTrim(String t, int p, long d, String r) {
                        throw new IllegalStateException("retention metric boom");
                    }
                    @Override public void recordDlqTrim(String t, long d, String r) {
                        throw new IllegalStateException("retention metric boom");
                    }
                });
        try {
            RedissonClient client = mock(RedissonClient.class);
            RStream<String, Object> stream = mock(RStream.class);
            RScript script = mock(RScript.class);
            when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
            when(client.getScript()).thenReturn(script);
            when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                    .thenReturn(null); // null lua id -> ternary null side + recording failure catch

            RedisBrokerPersistence persistence = new RedisBrokerPersistence(client,
                    MqOptions.builder().retentionMaxLenPerPartition(5).build());
            assertNull(persistence.append("t", 0, msg("p")));
        } finally {
            io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.setCollector(prev);
        }
    }

    @Test
    void appendFallbackAddMayReturnNullId() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(client.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("eval failed"));
        when(stream.add(any())).thenReturn(null);
        when(stream.size()).thenReturn(0L);

        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(5).build());
        assertNull(persistence.append("t", 0, msg("p")));
    }

    @Test
    void appendFallbackExactTrimSucceeds() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(client.getScript()).thenReturn(script);
        // 1st call: atomic XADD fails -> fallback; 2nd call: exact XTRIM succeeds
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("xadd failed"))
                .thenReturn("OK");
        when(stream.add(any())).thenReturn(new StreamMessageId(13, 0));
        when(stream.size()).thenReturn(1L);

        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(5).build());
        assertEquals("13-0", persistence.append("t", 0, msg("p")));
    }

    @Test
    void appendFallbackApproxTrimFailsAndHardCapRemoveFails() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(client.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("eval failed"));
        when(stream.add(any())).thenReturn(new StreamMessageId(14, 0));
        when(stream.size()).thenReturn(10L);
        Map<StreamMessageId, Map<String, Object>> old = new LinkedHashMap<>();
        old.put(new StreamMessageId(1, 0), Map.of());
        old.put(new StreamMessageId(2, 0), Map.of());
        when(stream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(old);
        doThrow(new IllegalStateException("xdel boom")).when(stream).remove(any(StreamMessageId.class));

        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(2).build());
        assertEquals("14-0", persistence.append("t", 0, msg("p")));
    }

    @Test
    void appendHardCapSizeProbeFailureIsSwallowed() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(client.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenThrow(new IllegalStateException("eval failed"));
        when(stream.add(any())).thenReturn(new StreamMessageId(15, 0));
        when(stream.size()).thenThrow(new IllegalStateException("size boom"));

        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(2).build());
        assertEquals("15-0", persistence.append("t", 0, msg("p")));
        verify(stream).add(any());
    }

    @Test
    void appendAcceptsMapPayloadAndNullMessageGuards() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(stream.add(any())).thenReturn(new StreamMessageId(16, 0));

        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(0).build());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("k", 1);
        assertDoesNotThrow(() -> persistence.append("t", 0, msg(payload)));
        assertNull(persistence.append(null, 0, msg("p")));
        assertNull(persistence.append(" ", 0, msg("p")));
        assertNull(persistence.append("t", 0, null));
    }
}
