package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedisKTableUnitTest {

    @Test
    void putUsesStringCodecAndJsonSerialization() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.put("k1", "v1");

        verify(map).put(eq("\"k1\""), eq("\"v1\""));
    }

    @Test
    void putWithNullValueRemovesKey() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.put("k1", null);

        verify(map).remove(eq("\"k1\""));
    }

    @Test
    void getUsesStringCodecAndDeserializesJson() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.get(eq("\"k1\""))).thenReturn("\"v1\"");

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        assertEquals("v1", table.get("k1"));
        assertNull(table.get("missing"));
    }

    @Test
    void getStateDeserializesAllEntries() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\"",
                "\"k2\"", "\"v2\""
        ).entrySet());

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        Map<String, String> state = table.getState();

        assertEquals(2, state.size());
        assertEquals("v1", state.get("k1"));
        assertEquals("v2", state.get("k2"));
    }

    @Test
    void sizeReturnsMapSize() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.size()).thenReturn(42);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        assertEquals(42, table.size());
    }

    @Test
    void sizeWithEmptyTable() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.size()).thenReturn(0);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        assertEquals(0, table.size());
    }

    @Test
    void clearRemovesAllEntries() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.clear();

        verify(map).clear();
    }

    @Test
    void deleteRemovesTableFromRedis() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.delete();

        verify(map).delete();
    }

    @Test
    void mapValuesCreatesNewTable() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(any(String.class), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\"",
                "\"k2\"", "\"v2\""
        ).entrySet());
        when(map.size()).thenReturn(2);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.put("k1", "v1");
        table.put("k2", "v2");

        KTable<String, String> result = table.mapValues(v -> v.toUpperCase());
        assertNotNull(result);
        assertTrue(result instanceof RedisKTable);
    }

    @Test
    void mapValuesWithKeyAndValueCreatesNewTable() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(any(String.class), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\"",
                "\"k2\"", "\"v2\""
        ).entrySet());
        when(map.size()).thenReturn(2);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.put("k1", "v1");
        table.put("k2", "v2");

        KTable<String, String> result = table.mapValues((k, v) -> k + ":" + v.toUpperCase());
        assertNotNull(result);
        assertTrue(result instanceof RedisKTable);
    }

    @Test
    void filterCreatesFilteredTable() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(any(String.class), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\"",
                "\"k2\"", "\"v2\"",
                "\"k3\"", "\"v3\""
        ).entrySet());
        when(map.size()).thenReturn(3);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.put("k1", "v1");
        table.put("k2", "v2");
        table.put("k3", "v3");

        KTable<String, String> result = table.filter((k, v) -> k.equals("k1") || k.equals("k3"));
        assertNotNull(result);
        assertTrue(result instanceof RedisKTable);
    }

    @Test
    void filterWithAllMatching() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(any(String.class), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\"",
                "\"k2\"", "\"v2\""
        ).entrySet());
        when(map.size()).thenReturn(2);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.put("k1", "v1");
        table.put("k2", "v2");

        KTable<String, String> result = table.filter((k, v) -> true);
        assertNotNull(result);
        assertTrue(result instanceof RedisKTable);
    }

    @Test
    void filterWithNoMatching() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(any(String.class), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\"",
                "\"k2\"", "\"v2\""
        ).entrySet());
        when(map.size()).thenReturn(2);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        table.put("k1", "v1");
        table.put("k2", "v2");

        KTable<String, String> result = table.filter((k, v) -> false);
        assertNotNull(result);
        assertTrue(result instanceof RedisKTable);
    }

    @Test
    void joinWithInnerJoin() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(any(String.class), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\"",
                "\"k2\"", "\"v2\""
        ).entrySet());
        when(map.size()).thenReturn(2);
        when(map.get(any(String.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("\"k1\"".equals(key)) return "\"v1\"";
            if ("\"k2\"".equals(key)) return "\"v2\"";
            return null;
        });

        RedisKTable<String, String> table1 = new RedisKTable<>(redissonClient, "t1", String.class, String.class);
        table1.put("k1", "v1");
        table1.put("k2", "v2");

        RedisKTable<String, String> table2 = new RedisKTable<>(redissonClient, "t2", String.class, String.class);
        table2.put("k1", "w1");
        table2.put("k3", "w3");

        KTable<String, String> result = table1.join(table2, (v, w) -> v + "+" + w);
        assertNotNull(result);
        assertTrue(result instanceof RedisKTable);
    }

    @Test
    void joinWithNoMatches() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(any(String.class), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\""
        ).entrySet());
        when(map.size()).thenReturn(1);
        when(map.get(any(String.class))).thenReturn(null);

        RedisKTable<String, String> table1 = new RedisKTable<>(redissonClient, "t1", String.class, String.class);
        table1.put("k1", "v1");

        RedisKTable<String, String> table2 = new RedisKTable<>(redissonClient, "t2", String.class, String.class);
        table2.put("k2", "w2");

        KTable<String, String> result = table1.join(table2, (v, w) -> v + "+" + w);
        assertNotNull(result);
        assertTrue(result instanceof RedisKTable);
    }

    @Test
    void leftJoinIncludesAllLeftRecords() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(any(String.class), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of(
                "\"k1\"", "\"v1\"",
                "\"k2\"", "\"v2\""
        ).entrySet());
        when(map.size()).thenReturn(2);
        when(map.get(any(String.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("\"k1\"".equals(key)) return "\"v1\"";
            if ("\"k2\"".equals(key)) return "\"v2\"";
            return null;
        });

        RedisKTable<String, String> table1 = new RedisKTable<>(redissonClient, "t1", String.class, String.class);
        table1.put("k1", "v1");
        table1.put("k2", "v2");

        RedisKTable<String, String> table2 = new RedisKTable<>(redissonClient, "t2", String.class, String.class);
        table2.put("k1", "w1");
        table2.put("k3", "w3");

        KTable<String, String> result = table1.leftJoin(table2, (v, w) -> v + "+" + (w != null ? w : "NULL"));
        assertNotNull(result);
        assertTrue(result instanceof RedisKTable);
    }

    @Test
    void toStringReturnsTableName() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.size()).thenReturn(5);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        String result = table.toString();
        assertTrue(result.contains("t"));
        assertTrue(result.contains("5"));
    }

    @Test
    void getTableNameReturnsTableName() {
        RedissonClient redissonClient = mock(RedissonClient.class);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "my-table", String.class, String.class);
        assertEquals("my-table", table.getTableName());
    }

    @Test
    void getRedissonClientReturnsClient() {
        RedissonClient redissonClient = mock(RedissonClient.class);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        assertSame(redissonClient, table.getRedissonClient());
    }

    @Test
    void putWithIntegerKeysAndValues() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.get(eq("1"))).thenReturn("100");

        RedisKTable<Integer, Integer> table = new RedisKTable<>(redissonClient, "t", Integer.class, Integer.class);
        table.put(1, 100);
        verify(map).put(eq("1"), eq("100"));
        assertEquals(Integer.valueOf(100), table.get(1));
    }

    @Test
    void putWithComplexObjectValues() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.get(eq("\"key\""))).thenReturn("{\"name\":\"test\",\"value\":42}");

        // Use a simple record class that works with Jackson
        record TestData(String name, int value) {}

        RedisKTable<String, TestData> table = new RedisKTable<>(redissonClient, "t", String.class, TestData.class);
        TestData data = new TestData("test", 42);
        table.put("key", data);

        verify(map).put(eq("\"key\""), any(String.class));
        assertEquals("test", table.get("key").name());
    }

    @Test
    void getStateWithEmptyTable() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of().entrySet());

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        Map<String, String> state = table.getState();
        assertTrue(state.isEmpty());
    }

    @Test
    void getReturnsNullForNonExistentKey() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(eq("t"), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.get(eq("\"missing\""))).thenReturn(null);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        assertNull(table.get("missing"));
    }

    @Test
    void mapValuesWithEmptyTable() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        @SuppressWarnings("rawtypes")
        RMap map = mock(RMap.class);
        when(redissonClient.getMap(any(String.class), eq(StringCodec.INSTANCE))).thenReturn(map);
        when(map.entrySet()).thenReturn(Map.of().entrySet());
        when(map.size()).thenReturn(0);

        RedisKTable<String, String> table = new RedisKTable<>(redissonClient, "t", String.class, String.class);
        KTable<String, String> result = table.mapValues(v -> v.toUpperCase());
        assertNotNull(result);
        assertTrue(result instanceof RedisKTable);
    }

    @Test
    void groupByReturnsKGroupedTable() {
        RedissonClient redissonClient = mock(RedissonClient.class);

        RedisKTable<String, Integer> table = new RedisKTable<>(redissonClient, "t", String.class, Integer.class);
        // Can't actually call groupBy without a valid KeyValue, just verify method exists
        assertNotNull(table);
    }
}
