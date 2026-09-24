package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers {@code RedisKTable#getState/join/leftJoin} and grouped aggregate/count/reduce without Redis. */
class RedisKTableJoinGroupCoverageTest {

    /** In-memory stand-in for Redisson maps so tables can be exercised end to end. */
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
    void getStateDeserializesAllEntries() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "src", String.class, String.class);
        table.put("k1", "v1");
        table.put("k2", "v2");

        Map<String, String> state = table.getState();
        assertEquals(Map.of("k1", "v1", "k2", "v2"), state);
    }

    @Test
    void joinWithRedisTableKeepsOnlyMatchingKeys() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> left = new RedisKTable<>(redissonClient, "left", String.class, String.class);
        RedisKTable<String, Integer> right = new RedisKTable<>(redissonClient, "right", String.class, Integer.class);
        left.put("k1", "a");
        left.put("k2", "b");
        right.put("k1", 1);
        right.put("k3", 3);

        KTable<String, String> joined = left.join(right, (l, r) -> l + r);
        Map<String, String> state = ((RedisKTable<String, String>) joined).getState();
        assertEquals(Map.of("k1", "a1"), state, "inner join drops unmatched keys");
    }

    @Test
    void joinWithInMemoryTableWorks() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> left = new RedisKTable<>(redissonClient, "left2", String.class, String.class);
        left.put("k1", "x");
        InMemoryKTable<String, Integer> right = new InMemoryKTable<>(Map.of("k1", 7));

        KTable<String, String> joined = left.join(right, (l, r) -> l + "-" + r);
        assertEquals("x-7", ((RedisKTable<String, String>) joined).get("k1"));
    }

    @Test
    void joinRejectsUnsupportedTableType() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> left = new RedisKTable<>(redissonClient, "left3", String.class, String.class);
        @SuppressWarnings("unchecked")
        KTable<String, String> unsupported = mock(KTable.class);
        assertThrows(UnsupportedOperationException.class, () -> left.join(unsupported, (l, r) -> l));
        assertThrows(UnsupportedOperationException.class, () -> left.leftJoin(unsupported, (l, r) -> l));
    }

    @Test
    void leftJoinKeepsUnmatchedLeftRowsWithNullRight() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, String> left = new RedisKTable<>(redissonClient, "lj", String.class, String.class);
        RedisKTable<String, String> right = new RedisKTable<>(redissonClient, "rj", String.class, String.class);
        left.put("k1", "a");
        left.put("k2", "b");
        right.put("k1", "R");

        KTable<String, String> joined = left.leftJoin(right, (l, r) -> r == null ? l + "?" : l + r);
        Map<String, String> state = ((RedisKTable<String, String>) joined).getState();
        assertEquals("aR", state.get("k1"));
        assertEquals("b?", state.get("k2"), "left rows without match survive with null right value");
    }

    @Test
    void groupByAggregateCountAndReduce() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, Integer> source = new RedisKTable<>(redissonClient, "grp", String.class, Integer.class);
        source.put("u1", 10);
        source.put("u2", 20);
        source.put("u3", 30);

        KTable<String, Integer> aggregated = source
                .groupBy((KTable.KeyValue<String, Integer> kv) -> kv.getKey().startsWith("u") ? "users" : "other")
                .aggregate(() -> 0, (k, v) -> v, (k, v) -> -v);
        assertEquals(30, ((RedisKTable<String, Integer>) aggregated).get("users"), "adder applied per entry, last wins in this snapshot semantics");

        KTable<String, Long> counted = source.groupBy((KTable.KeyValue<String, Integer> kv) -> "g").count();
        assertEquals(3L, ((RedisKTable<String, Long>) counted).get("g"));

        KTable<String, Integer> reduced = source.groupBy((KTable.KeyValue<String, Integer> kv) -> kv.getKey().substring(0, 1))
                .reduce((a, b) -> a + b, (a, b) -> a - b);
        assertTrue(((RedisKTable<String, Integer>) reduced).get("u") > 0);
    }

    @Test
    void groupedAggregateSkipsNullGroupKeys() {
        RedissonClient redissonClient = inMemoryRedisson();
        RedisKTable<String, Integer> source = new RedisKTable<>(redissonClient, "grp-null", String.class, Integer.class);
        source.put("k1", 1);
        KTable<String, Integer> aggregated = source
                .<String>groupBy((KTable.KeyValue<String, Integer> kv) -> null)
                .aggregate(() -> 0, (k, v) -> v, (k, v) -> v);
        assertTrue(((RedisKTable<String, Integer>) aggregated).getState().isEmpty());
    }
}
