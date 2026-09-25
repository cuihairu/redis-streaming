package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KGroupedTable;
import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the type-inference branches of {@link RedisKGroupedTable#aggregate}
 * and {@link RedisKGroupedTable#reduce}: null aggregate results must skip
 * value-class inference instead of failing.
 */
class RedisKGroupedTableInferenceCoverageTest {

    @SuppressWarnings("unchecked")
    private RedisKTable<String, String> sourceTable(RedissonClient redisson, Map<String, String> entries) {
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString(), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(entries.entrySet());
        return new RedisKTable<>(redisson, "src", String.class, String.class);
    }

    @Test
    void aggregateWithNullAdderResultSkipsValueClassInference() {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisKTable<String, String> source = sourceTable(redisson, Map.of("\"k1\"", "\"v1\""));

        AtomicInteger initializerCalls = new AtomicInteger();
        KGroupedTable<String, String> grouped = source.groupBy(kv -> kv.getKey());

        BiFunction<String, String, String> nullAdder = (k, v) -> null;
        RedisKTable<String, String> result = (RedisKTable<String, String>) grouped.aggregate(
                () -> {
                    initializerCalls.incrementAndGet();
                    return "seed";
                },
                nullAdder,
                nullAdder);

        assertEquals(1, initializerCalls.get());
        assertNull(result.get("k1"), "a null aggregate must leave the key absent in the result");
    }

    @Test
    void aggregateWithNullEntryValueSkipsValueClassInference() {
        RedissonClient redisson = mock(RedissonClient.class);
        // JSON "null" deserializes to a null table value
        RedisKTable<String, String> source = sourceTable(redisson, Map.of("\"k1\"", "null"));

        KGroupedTable<String, String> grouped = source.groupBy(kv -> kv.getKey());
        BiFunction<String, String, String> adder = (k, v) -> v;

        RedisKTable<String, String> result = (RedisKTable<String, String>) grouped.aggregate(() -> "seed", adder, adder);

        assertNull(result.get("k1"));
    }

    @Test
    void reduceWithNullEntryValueSkipsValueClassInference() {
        RedissonClient redisson = mock(RedissonClient.class);
        // JSON "null" deserializes to a null table value
        RedisKTable<String, String> source = sourceTable(redisson, Map.of("\"k1\"", "null"));

        KGroupedTable<String, String> grouped = source.groupBy(kv -> kv.getKey());
        BiFunction<String, String, String> adder = (a, b) -> a;

        RedisKTable<String, String> result =
                (RedisKTable<String, String>) grouped.reduce(adder, adder);

        assertNull(result.get("k1"), "a null reduced value must leave the key absent");
    }

    @Test
    void reduceWithNullAdderResultSkipsValueClassInference() {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisKTable<String, String> source = sourceTable(redisson, Map.of("\"k1\"", "\"v1\""));

        KGroupedTable<String, String> grouped = source.groupBy(kv -> kv.getKey());
        BiFunction<String, String, String> nullAdder = (a, b) -> null;

        RedisKTable<String, String> result = (RedisKTable<String, String>) grouped.reduce(nullAdder, nullAdder);

        assertNull(result.get("k1"), "a null reduction result must leave the key absent");
    }

    @Test
    void reduceCollapsesKeysAndDropsKeyWhenAdderYieldsNull() {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisKTable<String, String> source =
                sourceTable(redisson, Map.of("\"k1\"", "\"v1\"", "\"k2\"", "\"v2\""));

        KGroupedTable<String, String> grouped = source.groupBy(kv -> "g");
        AtomicInteger adderCalls = new AtomicInteger();
        BiFunction<String, String, String> adder = (a, b) -> {
            adderCalls.incrementAndGet();
            return null;
        };

        RedisKTable<String, String> result = (RedisKTable<String, String>) grouped.reduce(adder, adder);

        assertEquals(1, adderCalls.get(), "the adder runs for every additional entry of a key");
        assertNull(result.get("g"), "a null reduction result must leave the key absent");
    }
}
