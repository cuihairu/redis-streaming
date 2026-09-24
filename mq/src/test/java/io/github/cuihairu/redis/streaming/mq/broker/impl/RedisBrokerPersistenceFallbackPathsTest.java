package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Covers RedisBrokerPersistence.append fallback paths when the atomic Lua XADD fails
 * and the exact/approximate trim fallbacks.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisBrokerPersistenceFallbackPathsTest {

    @Test
    void appendFallsBackWhenLuaEvalFails() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(client.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any())).thenThrow(new IllegalStateException("eval failed"));
        when(stream.add(any())).thenReturn(new StreamMessageId(7, 0));
        when(stream.size()).thenReturn(0L);

        MqOptions options = MqOptions.builder().retentionMaxLenPerPartition(5).build();
        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client, options);
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("p");
        assertEquals("7-0", persistence.append("t", 0, m));
        verify(stream).add(any());
    }

    @Test
    void appendWithoutRetentionSkipsLuaEntirely() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(stream.add(any())).thenReturn(new StreamMessageId(8, 0));

        MqOptions options = MqOptions.builder().retentionMaxLenPerPartition(0).build();
        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client, options);
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("p");
        assertEquals("8-0", persistence.append("t", 0, m));
        verify(client, never()).getScript();
    }

    @Test
    void appendHardCapsWhenTrimCannotKeepUp() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(client.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(client.getScript()).thenReturn(script);
        // Lua eval fails so the two-step fallback with hard cap runs
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any())).thenThrow(new IllegalStateException("eval failed"));
        when(stream.add(any())).thenReturn(new StreamMessageId(9, 0));
        when(stream.size()).thenReturn(10L); // still above maxLen after trim attempts
        java.util.Map<StreamMessageId, java.util.Map<String, Object>> old = new java.util.LinkedHashMap<>();
        old.put(new StreamMessageId(1, 0), java.util.Map.of());
        old.put(new StreamMessageId(2, 0), java.util.Map.of());
        old.put(new StreamMessageId(3, 0), java.util.Map.of());
        when(stream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(old);

        MqOptions options = MqOptions.builder().retentionMaxLenPerPartition(2).build();
        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client, options);
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("p");
        assertEquals("9-0", persistence.append("t", 0, m));
        verify(stream, atLeastOnce()).remove(any(StreamMessageId.class));
    }
}
