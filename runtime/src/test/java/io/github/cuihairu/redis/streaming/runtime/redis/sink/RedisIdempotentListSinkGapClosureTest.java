package io.github.cuihairu.redis.streaming.runtime.redis.sink;

import io.github.cuihairu.redis.streaming.api.stream.IdempotentRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branches of {@link RedisIdempotentListSink}: {@code record.id} validation arms
 * (records cannot carry blank ids through their canonical constructor, so hostile/deserialized
 * envelopes are simulated with mocks), {@code encode} arms (null / String / object payloads)
 * and the {@code dedupTtl == null} defaulting of the Lua TTL argument.
 */
class RedisIdempotentListSinkGapClosureTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final List<Object[]> evalCalls = new java.util.ArrayList<>();
    private final RScript script = mock(RScript.class, inv -> {
        if ("eval".equals(inv.getMethod().getName())) {
            evalCalls.add(flat(inv.getArguments()));
        }
        return 1L;
    });

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RedisIdempotentListSink<Object> sink(Duration ttl) {
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        return new RedisIdempotentListSink<>(redisson, "dedup:{it}", "list:{it}", null, ttl);
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
    void invokeRejectsNullAndBlankIds() {
        RedisIdempotentListSink<Object> s = sink(Duration.ofDays(1));
        IdempotentRecord<Object> nullId = mock(IdempotentRecord.class);
        assertThrows(IllegalArgumentException.class, () -> s.invoke(nullId));
        IdempotentRecord<Object> blankId = mock(IdempotentRecord.class);
        when(blankId.id()).thenReturn("   ");
        assertThrows(IllegalArgumentException.class, () -> s.invoke(blankId));
    }

    @Test
    void invokeEncodesNullStringAndObjectPayloads() throws Exception {
        RedisIdempotentListSink<Object> s = sink(null);
        s.invoke(null);

        IdempotentRecord<Object> nullValue = mock(IdempotentRecord.class);
        when(nullValue.id()).thenReturn("id-null");
        s.invoke(nullValue);

        s.invoke(new IdempotentRecord<>("id-str", "plain"));
        s.invoke(new IdempotentRecord<>("id-num", 42));

        assertEquals(3, evalCalls.size());
        assertEquals("id-null", evalCalls.get(0)[4]);
        assertEquals("null", evalCalls.get(0)[5], "null payload encodes as literal null");
        assertEquals("0", evalCalls.get(0)[6], "null dedupTtl encodes as zero TTL");
        assertEquals("plain", evalCalls.get(1)[5], "String payload passes through");
        assertEquals("42", evalCalls.get(2)[5], "object payload is JSON encoded");
    }

    @Test
    void invokeClampsNegativeTtlToZero() throws Exception {
        RedisIdempotentListSink<Object> s = sink(Duration.ofSeconds(-5));
        s.invoke(new IdempotentRecord<>("id-ttl", "v"));
        assertEquals(1, evalCalls.size());
        assertEquals("0", evalCalls.get(0)[6], "negative TTL is clamped to zero");
    }
}
