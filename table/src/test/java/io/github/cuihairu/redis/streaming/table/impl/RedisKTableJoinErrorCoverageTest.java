package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers RedisKTable join/getState error wrappers and grouped null-key branches. */
class RedisKTableJoinErrorCoverageTest {

    private static RedissonClient inMemoryRedisson() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        Map<String, Map<String, String>> storage = new ConcurrentHashMap<>();
        when(redissonClient.getMap(anyString(), eq(StringCodec.INSTANCE))).thenAnswer(inv -> {
            Map<String, String> backing = storage.computeIfAbsent(inv.getArgument(0), k -> new ConcurrentHashMap<>());
            return mockRMap(backing);
        });
        return redissonClient;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RMap<String, String> mockRMap(Map<String, String> backing) {
        RMap<String, String> map = mock(RMap.class);
        when(map.get(anyString())).thenAnswer(inv -> {
            Object k = inv.getArgument(0);
            return backing.get(String.valueOf(k));
        });
        when(map.put(anyString(), anyString())).thenAnswer(inv -> {
            Object k = inv.getArgument(0);
            Object v = inv.getArgument(1);
            return backing.put(String.valueOf(k), String.valueOf(v));
        });
        when(map.remove(anyString())).thenAnswer(inv -> {
            Object k = inv.getArgument(0);
            return backing.remove(String.valueOf(k));
        });
        when(map.size()).thenAnswer(inv -> backing.size());
        when(map.entrySet()).thenAnswer(inv -> backing.entrySet());
        doAnswer(inv -> {
            backing.clear();
            return null;
        }).when(map).clear();
        when(map.delete()).thenAnswer(inv -> {
            backing.clear();
            return true;
        });
        return map;
    }

    @Test
    void joinAndLeftJoinWrapJoinerFailures() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> left = new RedisKTable<>(redissonClient, "jl", String.class, String.class);
        RedisKTable<String, String> right = new RedisKTable<>(redissonClient, "jr", String.class, String.class);
        left.put("k", "L");
        right.put("k", "R");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> left.join(right, (l, r) -> {
                    throw new IllegalStateException("joiner bug");
                }));
        assertTrue(ex.getMessage().contains("Join failed"), ex.getMessage());

        RuntimeException ex2 = assertThrows(RuntimeException.class,
                () -> left.leftJoin(right, (l, r) -> {
                    throw new IllegalStateException("joiner bug");
                }));
        assertTrue(ex2.getMessage().contains("Left join failed"), ex2.getMessage());
    }

    @Test
    void getStateWrapsDeserializationFailures() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap<String, String> map = mock(RMap.class);
        Map<String, String> backing = new ConcurrentHashMap<>();
        backing.put("k1", "not-json-for-int");
        when(map.entrySet()).thenAnswer(inv -> backing.entrySet());
        when(redissonClient.<String, String>getMap(anyString(), eq(StringCodec.INSTANCE))).thenReturn(map);

        RedisKTable<String, Integer> table = new RedisKTable<>(redissonClient, "bad", String.class, Integer.class);
        RuntimeException ex = assertThrows(RuntimeException.class, table::getState);
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("Deserialization failed"));
    }

    @Test
    void groupedCountAndReduceSkipNullKeys() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, Integer> source = new RedisKTable<>(redissonClient, "grp2", String.class, Integer.class);
        source.put("k1", 1);

        KTable<String, Long> counted = source.<String>groupBy(kv -> null).count();
        assertTrue(((RedisKTable<String, Long>) counted).getState().isEmpty());

        KTable<String, Integer> reduced = source.<String>groupBy(kv -> null).reduce((a, b) -> a, (a, b) -> a);
        assertTrue(((RedisKTable<String, Integer>) reduced).getState().isEmpty());
    }
}
