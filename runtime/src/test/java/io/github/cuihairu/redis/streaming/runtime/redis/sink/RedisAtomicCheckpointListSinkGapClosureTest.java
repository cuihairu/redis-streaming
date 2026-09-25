package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branches of {@link RedisAtomicCheckpointListSink}: {@code dedupTtl == null} defaulting,
 * payload encoding arms (null / String / object) and group batching across pipelines.
 *
 * <p>Note: {@code if (rs.isEmpty()) continue;} inside {@code onCheckpointComplete} is dead code —
 * every buffered record is added to its group list, so no group list can ever be empty; the branch
 * is left uncovered.</p>
 */
class RedisAtomicCheckpointListSinkGapClosureTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final List<Object[]> evalCalls = new java.util.ArrayList<>();
    private final RScript script = mock(RScript.class, inv -> {
        if ("eval".equals(inv.getMethod().getName())) {
            evalCalls.add(flat(inv.getArguments()));
        }
        return 1L;
    });

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RedisAtomicCheckpointListSink<Object> sink(Duration ttl) {
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        return new RedisAtomicCheckpointListSink<>(redisson, "dedup:{it}", "list:{it}", null, ttl);
    }

    private static RedisExactlyOnceRecord<Object> record(String topic, String group, int pid,
                                                         String messageId, String idKey, Object value) {
        RedisExactlyOnceRecord<Object> r = mock(RedisExactlyOnceRecord.class);
        when(r.topic()).thenReturn(topic);
        when(r.consumerGroup()).thenReturn(group);
        when(r.partitionId()).thenReturn(pid);
        when(r.messageId()).thenReturn(messageId);
        when(r.idempotencyKey()).thenReturn(idKey);
        when(r.value()).thenReturn(value);
        return r;
    }


    /** Normalizes Mockito's varargs representation (expanded or trailing Object[]). */
    private static Object[] flat(Object[] raw) {
        if (raw.length == 5 && raw[4] instanceof Object[] arr) {
            Object[] out = new Object[4 + arr.length];
            System.arraycopy(raw, 0, out, 0, 4);
            System.arraycopy(arr, 0, out, 4, arr.length);
            return out;
        }
        return raw;
    }
    @Test
    void checkpointCompleteWithNullTtlEncodesAllPayloadShapes() throws Exception {
        RedisAtomicCheckpointListSink<Object> s = sink(null);
        s.invoke(null);
        s.invoke(record("t", "g", 0, "1-1", "k-null", null));
        s.invoke(record("t", "g", 0, "1-2", "k-str", "plain"));
        s.invoke(record("t", "g", 0, "1-3", "k-num", 7));
        s.onCheckpointComplete(1L);

        assertEquals(1, evalCalls.size());
        Object[] args = evalCalls.get(0);
        assertEquals("g", args[4]);
        assertEquals("0", args[5], "null dedupTtl encodes as zero TTL");
        assertEquals("k-null", args[6]);
        assertEquals("null", args[7], "null value encodes as literal null");
        assertEquals("plain", args[10], "String value passes through");
        assertEquals("7", args[13], "object value is JSON encoded");
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkpointCompleteGroupsByTopicGroupPartition() throws Exception {
        RedisAtomicCheckpointListSink<Object> s = sink(Duration.ofMinutes(1));
        s.invoke(record("t", "g", 0, "1-1", "k1", "a"));
        s.invoke(record("t", "g", 1, "1-1", "k2", "b"));
        s.invoke(record("t2", "g", 0, "2-1", "k3", "c"));
        s.onCheckpointComplete(2L);

        assertEquals(3, evalCalls.size());
        assertTrue(evalCalls.stream().anyMatch(a -> String.valueOf(((List<?>) a[3]).get(2)).contains("t")),
                "partition stream key must be derived per group");
        assertTrue(evalCalls.stream().anyMatch(a -> String.valueOf(((List<?>) a[3]).get(3)).contains("t2")),
                "distinct topics must produce their own frontier key");
    }

    @Test
    void checkpointAbortAndRestoreDiscardBufferedRecords() throws Exception {
        RedisAtomicCheckpointListSink<Object> s = sink(null);
        s.invoke(record("t", "g", 0, "1-1", "k1", "a"));
        s.onCheckpointAbort(1L, new IllegalStateException("x"));
        s.onCheckpointComplete(1L);
        verify(script, times(0)).eval(any(), anyString(), any(), anyList(), any(Object[].class));

        s.invoke(record("t", "g", 0, "1-1", "k1", "a"));
        s.onCheckpointRestore(1L);
        s.onCheckpointComplete(2L);
        verify(script, times(0)).eval(any(), anyString(), any(), anyList(), any(Object[].class));
    }
}
