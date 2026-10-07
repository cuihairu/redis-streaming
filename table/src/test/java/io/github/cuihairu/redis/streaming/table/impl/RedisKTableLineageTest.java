package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the derivation lineage added by B-24: every materialized
 * transformation registers under {@code <table>:__derived}, per-operation generation
 * retention bounds repeated derivations, and {@code delete()} reclaims the whole tree.
 */
class RedisKTableLineageTest {

    @Test
    void repeatedDerivationsStayBoundedByRetention() {
        RedissonClient redisson = inMemoryRedisson();
        RedisKTable<String, String> table = new RedisKTable<>(redisson, "t", String.class, String.class);
        table.put("k", "v");
        table.setDerivedTableRetention(2);

        List<RedisKTable<String, Integer>> generations = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            generations.add((RedisKTable<String, Integer>) table.mapValues(String::length));
        }

        // only the two newest generations survive in Redis, oldest ones are gone
        assertEquals(2, countLiveKeys("t:mapValues:"),
                "repeated mapValues derivations must be bounded by the retention count");
        assertTrue(isLive(generations.get(2).getTableName()), "second-newest generation stays");
        assertTrue(isLive(generations.get(3).getTableName()), "newest generation stays");
        assertFalse(isLive(generations.get(0).getTableName()), "oldest generation is reclaimed");
        assertFalse(isLive(generations.get(1).getTableName()));

        // the newest generation still reads its content back
        assertEquals(1, generations.get(3).get("k"));

        // lineage hash keeps at most the retained same-op entries
        assertEquals(2, sizeOf("t" + RedisKTable.DERIVED_LINEAGE_SUFFIX));
    }

    @Test
    void differentOperationsDoNotReclaimEachOther() {
        RedissonClient redisson = inMemoryRedisson();
        RedisKTable<String, String> table = new RedisKTable<>(redisson, "t", String.class, String.class);
        table.put("k", "v");
        table.setDerivedTableRetention(1);

        RedisKTable<String, Integer> mapped = (RedisKTable<String, Integer>) table.mapValues(String::length);
        RedisKTable<String, String> filtered = (RedisKTable<String, String>) table.filter((k, v) -> true);

        assertTrue(isLive(mapped.getTableName()), "mapValues generation is untouched by filter");
        assertTrue(isLive(filtered.getTableName()), "filter generation is untouched by mapValues");
    }

    @Test
    void deleteCascadesThroughTheWholeDerivationTree() {
        RedissonClient redisson = inMemoryRedisson();
        RedisKTable<String, String> table = new RedisKTable<>(redisson, "t", String.class, String.class);
        table.put("k", "v");

        RedisKTable<String, Integer> mapped = (RedisKTable<String, Integer>) table.mapValues(String::length);
        RedisKTable<String, Integer> filtered = (RedisKTable<String, Integer>) mapped.filter((k, v) -> true);
        KTable<String, Long> counted = table.groupBy(kv -> "g").count();

        table.delete();

        assertEquals(0, liveKeyCount(),
                "delete() must remove the base table, every derived table and every lineage hash");
        assertFalse(isLive(mapped.getTableName()));
        assertFalse(isLive(filtered.getTableName()));
        assertFalse(isLive(((RedisKTable<String, Long>) counted).getTableName()));
    }

    @Test
    void childDeleteReclaimsOnlyItsOwnSubtree() {
        RedissonClient redisson = inMemoryRedisson();
        RedisKTable<String, String> table = new RedisKTable<>(redisson, "t", String.class, String.class);
        table.put("k", "v");

        RedisKTable<String, Integer> mapped = (RedisKTable<String, Integer>) table.mapValues(String::length);
        RedisKTable<String, Integer> filtered = (RedisKTable<String, Integer>) mapped.filter((k, v) -> true);

        mapped.delete();

        assertFalse(isLive(mapped.getTableName()));
        assertFalse(isLive(filtered.getTableName()), "child delete reclaims the grandchild too");
        assertTrue(isLive(table.getTableName()), "the source table stays");
    }

    @Test
    void foreignLineageEntriesAreSkippedNotBroken() {
        RedissonClient redisson = inMemoryRedisson();
        RedisKTable<String, String> table = new RedisKTable<>(redisson, "t", String.class, String.class);
        table.put("k", "v");
        // a foreign value sharing the lineage namespace must not break derivations or cleanup
        redisson.getMap("t" + RedisKTable.DERIVED_LINEAGE_SUFFIX, StringCodec.INSTANCE)
                .put("not-a-child", "not-json{");

        RedisKTable<String, Integer> mapped = (RedisKTable<String, Integer>) table.mapValues(String::length);

        assertEquals(1, mapped.get("k"));
        assertEquals("not-json{",
                redisson.getMap("t" + RedisKTable.DERIVED_LINEAGE_SUFFIX, StringCodec.INSTANCE).get("not-a-child"),
                "foreign entries are left alone");
    }

    @Test
    void groupedResultsRegisterLineageAndRespectRetention() {
        RedissonClient redisson = inMemoryRedisson();
        RedisKTable<String, String> table = new RedisKTable<>(redisson, "t", String.class, String.class);
        table.put("k", "v");
        table.setDerivedTableRetention(1);

        KTable<String, Long> first = table.groupBy(kv -> "g").count();
        KTable<String, Long> second = table.groupBy(kv -> "g").count();

        String firstName = ((RedisKTable<String, Long>) first).getTableName();
        assertTrue(firstName.startsWith("t:groupBy:count:"), "grouped results materialize under the source namespace");
        assertEquals(1, countLiveKeys("t:groupBy:count:"),
                "repeated count() derivations are bounded by the retention count");
        assertTrue(isLive(((RedisKTable<String, Long>) second).getTableName()), "the newest grouped result stays");
        assertFalse(isLive(firstName), "the older grouped result is reclaimed");
    }

    @Test
    void derivedTablesInheritTtl() {
        RedissonClient redisson = inMemoryRedisson();
        RedisKTable<String, String> table = new RedisKTable<>(redisson, "t", String.class, String.class);
        table.put("k", "v");
        Duration ttl = Duration.ofMinutes(5);
        table.setDerivedTableTtl(ttl);

        RedisKTable<String, Integer> mapped = (RedisKTable<String, Integer>) table.mapValues(String::length);

        assertEquals(ttl, table.getDerivedTableTtl());
        assertEquals(ttl, mapped.getDerivedTableTtl(), "children inherit the ttl for their own derivations");
        assertEquals(ttl, expireAppliedTo.get(mapped.getTableName()),
                "the derived hash gets the configured ttl");
    }

    @Test
    void retentionRejectsNonPositiveValues() {
        RedisKTable<String, String> table = new RedisKTable<>(inMemoryRedisson(), "t", String.class, String.class);
        assertThrows(IllegalArgumentException.class, () -> table.setDerivedTableRetention(0));
        assertThrows(IllegalArgumentException.class, () -> table.setDerivedTableRetention(-3));
        assertEquals(RedisKTable.DEFAULT_DERIVED_RETENTION, table.getDerivedTableRetention());
    }

    // ---- storage-backed Redisson mock (mirrors RedisKTableOperationsUnitTest) ----

    /** name -> backing hash; every key the client touches gets an entry here. */
    private static final Map<String, Map<String, String>> storage = new ConcurrentHashMap<>();
    private static final Map<String, Duration> expireAppliedTo = new ConcurrentHashMap<>();

    /** A key is "live" when its backing hash is non-empty (mocks have no key iteration). */
    private static boolean isLive(String name) {
        Map<String, String> backing = storage.get(name);
        return backing != null && !backing.isEmpty();
    }

    private static int countLiveKeys(String prefix) {
        return (int) storage.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && !e.getValue().isEmpty())
                .count();
    }

    private static int liveKeyCount() {
        return (int) storage.values().stream().filter(m -> !m.isEmpty()).count();
    }

    private static int sizeOf(String name) {
        return storage.getOrDefault(name, Map.of()).size();
    }

    private static RedissonClient inMemoryRedisson() {
        storage.clear();
        expireAppliedTo.clear();
        RedissonClient redisson = mock(RedissonClient.class);

        when(redisson.getMap(anyString(), eq(StringCodec.INSTANCE))).thenAnswer(inv -> {
            String name = inv.getArgument(0);
            return mockRMap(name, storage.computeIfAbsent(name, ignored -> new ConcurrentHashMap<>()));
        });

        return redisson;
    }

    @SuppressWarnings("rawtypes")
    private static RMap mockRMap(String name, Map<String, String> backing) {
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
        when(map.readAllMap()).thenAnswer(inv -> new java.util.HashMap<>(backing));
        when(map.delete()).thenAnswer(inv -> {
            boolean existed = !backing.isEmpty();
            backing.clear();
            return existed;
        });
        when(map.expire(any(Duration.class))).thenAnswer(inv -> {
            expireAppliedTo.put(name, inv.getArgument(0));
            return true;
        });

        return map;
    }
}
