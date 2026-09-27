package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQ-13 regression: when the Lua trim attempts could not keep the stream at maxLen, the
 * rescue hard-cap did range(toDelete, MIN, MAX) in ONE call — materializing the entire
 * backlog (field maps included) into the heap just to collect entry ids. On a partition
 * whose backlog far exceeds a shrunk retentionMaxLenPerPartition this is an OOM risk.
 * The rescue scan must page with a bounded count.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisBrokerPersistenceHardCapPagingTest {

    @BeforeAll
    static void seam() {
        System.setProperty("mq.retention.test.hardCapPageSize", "2");
    }

    @AfterAll
    static void restore() {
        System.clearProperty("mq.retention.test.hardCapPageSize");
    }

    private static Map<StreamMessageId, Map<String, Object>> page(StreamMessageId... ids) {
        Map<StreamMessageId, Map<String, Object>> m = new LinkedHashMap<>();
        for (StreamMessageId id : ids) m.put(id, Map.of("payload", "x"));
        return m;
    }

    @Test
    void hardCapRescuePagesTheIdScanInsteadOfLoadingTheWholeBacklog() {
        RedissonClient client = mock(RedissonClient.class);
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);
        when(client.getStream(anyString(), any(StringCodec.class))).thenReturn((RStream) stream);
        when(client.getScript()).thenReturn(script);
        when(client.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        // every Lua attempt fails -> two-step fallback + hard-cap rescue path runs
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList(), any())).thenThrow(new IllegalStateException("eval failed"));
        when(stream.add(any())).thenReturn(new StreamMessageId(9, 0));
        when(stream.size()).thenReturn(7L); // maxLen=2 -> toDelete=5, larger than the page size

        AtomicInteger calls = new AtomicInteger();
        when(stream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenAnswer(inv -> {
            switch (calls.incrementAndGet()) {
                case 1: return page(new StreamMessageId(1, 0), new StreamMessageId(2, 0));
                case 2: return page(new StreamMessageId(2, 1), new StreamMessageId(3, 0));
                case 3: return page(new StreamMessageId(4, 0), new StreamMessageId(5, 0));
                default: return Map.of();
            }
        });

        MqOptions options = MqOptions.builder().retentionMaxLenPerPartition(2).build();
        RedisBrokerPersistence persistence = new RedisBrokerPersistence(client, options);
        Message m = new Message();
        m.setTopic("t");
        m.setPayload("p");
        persistence.append("t", 0, m);

        // MQ-13, stated first so the old-code failure is exactly the unbounded scan:
        // old code made ONE range() call with count = toDelete (5), new code pages by 2
        org.mockito.ArgumentCaptor<Integer> counts = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(stream, atLeastOnce()).range(counts.capture(), any(StreamMessageId.class), any(StreamMessageId.class));
        List<Integer> seen = counts.getAllValues();
        for (int count : seen) {
            assertTrue(count <= 2, "every range page must be bounded by the page size, got " + seen);
        }
        // exactly 5 ids removed, via 3 bounded pages
        verify(stream, times(5)).remove(any(StreamMessageId.class));
        verify(stream, times(3)).range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class));
    }
}
