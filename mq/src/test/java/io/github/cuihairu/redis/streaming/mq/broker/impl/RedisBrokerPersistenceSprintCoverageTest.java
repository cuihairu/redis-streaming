package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamMessageId;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link RedisBrokerPersistence#append}: value normalization,
 * the approximate-trim fallback chain and the hard-cap trim accounting.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisBrokerPersistenceSprintCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> stream;
    private RScript script;

    @BeforeEach
    void setUp() throws Exception {
        io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure("streaming:mq", "stream:topic");
        client = mock(RedissonClient.class);
        stream = mock(RStream.class);
        script = evalScript();
        when(client.getStream(anyString(), any())).thenReturn((RStream) stream);
        when(client.getScript()).thenReturn(script);
        when(client.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(stream.add(any())).thenReturn(new StreamMessageId(7, 0));
    }

    private final java.util.LinkedList<Object> evalResults = new java.util.LinkedList<>();

    @SuppressWarnings("unchecked")
    private RScript evalScript() {
        return mock(RScript.class, inv -> {
            if ("eval".equals(inv.getMethod().getName())) {
                Object next = evalResults.poll();
                if (next instanceof RuntimeException re) {
                    throw re;
                }
                return next;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(inv);
        });
    }

    private static Message message(Object payload) {
        Message m = new Message();
        m.setTopic("t");
        m.setPayload(payload);
        return m;
    }

    @Test
    void appendStringifiesBooleanPayload() throws Exception {
        RedisBrokerPersistence p = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(0).build());
        String id = p.append("t", 0, message(Boolean.TRUE));
        assertNotNull(id);

        var captor = org.mockito.ArgumentCaptor.forClass(StreamAddArgs.class);
        verify(stream).add(captor.capture());
        Map<String, Object> entries = extractEntries(captor.getValue());
        assertEquals("true", entries.get("payload"));
    }

    @Test
    void appendFallsBackToApproximateTrimWhenExactTrimUnavailable() throws Exception {
        RedisBrokerPersistence p = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(2).build());
        evalResults.add(new IllegalStateException("xadd maxlen unsupported"));
        evalResults.add(new IllegalStateException("xtrim exact unsupported"));
        evalResults.add("0");

        assertNotNull(p.append("t", 0, message("p")));
        verify(stream).add(any());
    }

    @Test
    void appendSwallowsApproximateTrimFailure() throws Exception {
        RedisBrokerPersistence p = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(2).build());
        evalResults.add(new IllegalStateException("xadd maxlen unsupported"));
        evalResults.add(new IllegalStateException("xtrim exact unsupported"));
        evalResults.add(new IllegalStateException("xtrim approx unsupported"));

        assertNotNull(p.append("t", 0, message("p")));
        verify(stream).add(any());
    }

    @Test
    void appendHardCapsOversizedStreamAfterTrim() throws Exception {
        RedisBrokerPersistence p = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(2).build());
        evalResults.add(new IllegalStateException("xadd maxlen unsupported"));
        evalResults.add("0"); // exact trim succeeds
        when(stream.size()).thenReturn(5L);
        Map<StreamMessageId, Map<String, Object>> old = new LinkedHashMap<>();
        old.put(new StreamMessageId(1, 0), Map.of());
        old.put(new StreamMessageId(2, 0), Map.of());
        old.put(new StreamMessageId(3, 0), Map.of());
        when(stream.range(org.mockito.ArgumentMatchers.anyInt(), any(), any())).thenReturn(old);

        assertNotNull(p.append("t", 0, message("p")));
        verify(stream, times(3)).remove(any(StreamMessageId.class));
    }

    @Test
    void appendKeepsSingleRemovalWhenRangeShrinksDuringTrim() throws Exception {
        RedisBrokerPersistence p = new RedisBrokerPersistence(client,
                MqOptions.builder().retentionMaxLenPerPartition(1).build());
        evalResults.add(new IllegalStateException("xadd maxlen unsupported"));
        evalResults.add("0");
        when(stream.size()).thenReturn(3L);
        Map<StreamMessageId, Map<String, Object>> old = new LinkedHashMap<>();
        old.put(new StreamMessageId(1, 0), Map.of());
        when(stream.range(org.mockito.ArgumentMatchers.anyInt(), any(), any())).thenReturn(old);

        assertNotNull(p.append("t", 0, message("p")));
        verify(stream, times(1)).remove(any(StreamMessageId.class));
    }

    private static Map<String, Object> extractEntries(Object addArgs) throws Exception {
        for (Class<?> c = addArgs.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return (Map<String, Object>) f.get(addArgs);
                }
            }
        }
        throw new IllegalStateException("no entries map found on " + addArgs.getClass());
    }
}
