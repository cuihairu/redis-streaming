package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the type-inference branches of {@link RedisKTable#mapValues},
 * {@link RedisKTable#join} and {@link RedisKTable#leftJoin}: null mapped or
 * joined values must skip class inference and fall back to {@code Object.class}.
 */
class RedisKTableInferenceCoverageTest {

    @SuppressWarnings("unchecked")
    private RMap<String, String> mapWith(RedissonClient redisson, Map<String, String> entries) {
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString(), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(entries.entrySet());
        return map;
    }

    @Test
    void mapValuesFunctionSkipsInferenceForNullResults() {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mapWith(redisson, Map.of("\"k1\"", "\"v1\"", "\"k2\"", "\"v2\""));

        RedisKTable<String, String> table = new RedisKTable<>(redisson, "src", String.class, String.class);
        Function<String, String> toNull = v -> null;

        RedisKTable<String, String> result = (RedisKTable<String, String>) table.mapValues(toNull);

        verify(map).remove("\"k1\"");
        verify(map).remove("\"k2\"");
        assertNull(result.get("k1"), "a null mapped value leaves the key absent in the result");
    }

    @Test
    void mapValuesFunctionInfersClassForNonNullResults() {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mapWith(redisson, Map.of("\"k1\"", "\"v1\""));

        RedisKTable<String, String> table = new RedisKTable<>(redisson, "src", String.class, String.class);
        RedisKTable<String, String> result = (RedisKTable<String, String>) table.mapValues((Function<String, String>) v -> v + "!");

        verify(map).put("\"k1\"", "\"v1!\"");
        assertNull(result.get("k1"));
    }

    @Test
    void mapValuesBiFunctionSkipsInferenceForNullResults() {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mapWith(redisson, Map.of("\"k1\"", "\"v1\""));

        RedisKTable<String, String> table = new RedisKTable<>(redisson, "src", String.class, String.class);
        BiFunction<String, String, String> toNull = (k, v) -> null;

        RedisKTable<String, String> result = (RedisKTable<String, String>) table.mapValues(toNull);

        verify(map).remove("\"k1\"");
        assertNull(result.get("k1"));
    }

    @Test
    void innerJoinSkipsInferenceForNullJoinResults() {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mapWith(redisson, Map.of("\"k1\"", "\"v1\""));

        RedisKTable<String, String> table = new RedisKTable<>(redisson, "src", String.class, String.class);
        InMemoryKTable<String, String> other = new InMemoryKTable<>(Map.of("k1", "o1"));

        BiFunction<String, String, String> joiner = (l, r) -> null;
        RedisKTable<String, String> result = (RedisKTable<String, String>) table.join(other, joiner);

        verify(map).remove("\"k1\"");
        assertNull(result.get("k1"), "null join results must be dropped by the result write");
    }

    @Test
    void leftJoinPassesNullForMissingKeysAndSkipsInference() {
        RedissonClient redisson = mock(RedissonClient.class);
        mapWith(redisson, Map.of("\"k1\"", "\"v1\"", "\"k2\"", "\"v2\""));

        RedisKTable<String, String> table = new RedisKTable<>(redisson, "src", String.class, String.class);
        InMemoryKTable<String, String> other = new InMemoryKTable<>(Map.of("k1", "o1"));

        @SuppressWarnings("unchecked")
        BiFunction<String, String, String> joiner = mock(BiFunction.class);
        when(joiner.apply(any(), any())).thenReturn(null);

        RedisKTable<String, String> result = (RedisKTable<String, String>) table.leftJoin(other, joiner);

        verify(joiner).apply(any(), eq("o1"));
        verify(joiner).apply(any(), isNull());
        assertNull(result.get("k1"), "null join results must fall back to Object.class without failing");
    }
}
