package io.github.cuihairu.redis.streaming.sink.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers RedisHashSink writeBatch TTL branch and null-value serialization. */
class RedisHashSinkTtlWriteBatchCoverageTest {

    @Test
    @SuppressWarnings("unchecked")
    void writeBatchWithTtlExpiresHash() {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString())).thenReturn(map);

        RedisHashSink<String, Object> sink = new RedisHashSink<>(redisson, "h-ttl",
                new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofSeconds(30));
        java.util.Map<String, Object> entries = new java.util.LinkedHashMap<>();
        entries.put("k", "v");
        entries.put("n", 42);
        sink.writeBatch(entries);
        verify(map).putAll(any(Map.class));
        verify(map).expire(eq(Duration.ofSeconds(30)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeBatchTtlPathPropagatesFailures() {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString())).thenReturn(map);
        doThrow(new IllegalStateException("boom")).when(map).putAll(any(Map.class));

        RedisHashSink<String, Object> sink = new RedisHashSink<>(redisson, "h-ttl2",
                new com.fasterxml.jackson.databind.ObjectMapper(), Duration.ofSeconds(5));
        assertThrows(RuntimeException.class, () -> sink.writeBatch(Map.of("k", "v")));
    }
}
