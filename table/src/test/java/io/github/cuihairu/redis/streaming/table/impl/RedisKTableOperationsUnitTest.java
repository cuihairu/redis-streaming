package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisKTableOperationsUnitTest {

    @Test
    void mapValuesAndFilterWorkWithoutRedis() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);

        table.put("k1", "hello");
        table.put("k2", "world");

        KTable<String, Integer> mapped = table.mapValues(String::length);
        assertTrue(mapped instanceof RedisKTable);
        RedisKTable<String, Integer> mappedTable = (RedisKTable<String, Integer>) mapped;

        assertEquals(5, mappedTable.get("k1"));
        assertEquals(5, mappedTable.get("k2"));

        KTable<String, String> filtered = table.filter((k, v) -> v.startsWith("h"));
        assertTrue(filtered instanceof RedisKTable);
        RedisKTable<String, String> filteredTable = (RedisKTable<String, String>) filtered;

        assertEquals("hello", filteredTable.get("k1"));
        assertNull(filteredTable.get("k2"));
    }

    @Test
    void joinAndLeftJoinWorkWithoutRedis() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> left = new RedisKTable<>(redissonClient, "left", String.class, String.class);
        RedisKTable<String, String> right = new RedisKTable<>(redissonClient, "right", String.class, String.class);

        left.put("a", "L1");
        left.put("b", "L2");
        left.put("c", "L3");

        right.put("a", "R1");
        right.put("b", "R2");

        KTable<String, String> joined = left.join(right, (l, r) -> l + "+" + r);
        assertTrue(joined instanceof RedisKTable);
        RedisKTable<String, String> joinedTable = (RedisKTable<String, String>) joined;
        assertEquals("L1+R1", joinedTable.get("a"));
        assertEquals("L2+R2", joinedTable.get("b"));
        assertNull(joinedTable.get("c"));

        KTable<String, String> leftJoined = left.leftJoin(right, (l, r) -> l + "+" + (r == null ? "null" : r));
        assertTrue(leftJoined instanceof RedisKTable);
        RedisKTable<String, String> leftJoinedTable = (RedisKTable<String, String>) leftJoined;
        assertEquals("L1+R1", leftJoinedTable.get("a"));
        assertEquals("L2+R2", leftJoinedTable.get("b"));
        assertEquals("L3+null", leftJoinedTable.get("c"));
    }

    @Test
    void toStreamEmitsSnapshotEntries() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, Integer> table = new RedisKTable<>(redissonClient, "t", String.class, Integer.class);
        table.put("a", 1);
        table.put("b", 2);

        List<KTable.KeyValue<String, Integer>> out = new ArrayList<>();
        table.toStream().addSink(out::add);

        Map<String, Integer> asMap = new LinkedHashMap<>();
        for (KTable.KeyValue<String, Integer> kv : out) {
            asMap.put(kv.getKey(), kv.getValue());
        }
        assertEquals(Map.of("a", 1, "b", 2), asMap);
    }

    @Test
    void groupByCountAndReduceWorkWithoutRedis() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, Integer> table = new RedisKTable<>(redissonClient, "t", String.class, Integer.class);
        table.put("apple", 1);
        table.put("apricot", 2);
        table.put("banana", 3);

        var grouped = table.groupBy(kv -> kv.getKey().substring(0, 1));

        KTable<String, Long> counts = grouped.count();
        assertTrue(counts instanceof RedisKTable);
        RedisKTable<String, Long> countsTable = (RedisKTable<String, Long>) counts;
        assertEquals(2L, countsTable.get("a"));
        assertEquals(1L, countsTable.get("b"));

        KTable<String, Integer> reduced = grouped.reduce(Integer::sum, (a, b) -> a - b);
        assertTrue(reduced instanceof RedisKTable);
        RedisKTable<String, Integer> reducedTable = (RedisKTable<String, Integer>) reduced;
        assertEquals(3, reducedTable.get("a"));
        assertEquals(3, reducedTable.get("b"));
    }

    private static RedissonClient inMemoryRedisson() {
        RedissonClient redisson = mock(RedissonClient.class);
        Map<String, Map<String, String>> tableStorage = new ConcurrentHashMap<>();

        when(redisson.getMap(anyString(), eq(StringCodec.INSTANCE))).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            Map<String, String> backing = tableStorage.computeIfAbsent(name, ignored -> new ConcurrentHashMap<>());
            return mockRMap(backing);
        });

        return redisson;
    }

        @SuppressWarnings("rawtypes")
    private static RMap mockRMap(Map<String, String> backing) {
        RMap map = mock(RMap.class);

        when(map.get(any())).thenAnswer(inv -> {
            Object k = inv.getArgument(0);
            return backing.get(String.valueOf(k));
        });
        when(map.put(any(), any())).thenAnswer(inv -> {
            Object k = inv.getArgument(0);
            Object v = inv.getArgument(1);
            return backing.put(String.valueOf(k), String.valueOf(v));
        });
        when(map.remove(any())).thenAnswer(inv -> {
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
}
