package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KGroupedTable;
import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for InMemoryKTable
 */
class InMemoryKTableTest {

    private InMemoryKTable<String, Integer> table;

    @BeforeEach
    void setUp() {
        table = new InMemoryKTable<>();
    }

    @Test
    void testPutAndGet() {
        table.put("a", 1);
        table.put("b", 2);
        table.put("c", 3);

        assertEquals(1, table.get("a"));
        assertEquals(2, table.get("b"));
        assertEquals(3, table.get("c"));
        assertNull(table.get("d"));
    }

    @Test
    void testPutNullRemovesEntry() {
        table.put("a", 1);
        assertEquals(1, table.size());

        table.put("a", null);
        assertEquals(0, table.size());
        assertNull(table.get("a"));
    }

    @Test
    void testSize() {
        assertEquals(0, table.size());

        table.put("a", 1);
        assertEquals(1, table.size());

        table.put("b", 2);
        assertEquals(2, table.size());

        table.put("a", 10);
        assertEquals(2, table.size());
    }

    @Test
    void testClear() {
        table.put("a", 1);
        table.put("b", 2);
        assertEquals(2, table.size());

        table.clear();
        assertEquals(0, table.size());
        assertNull(table.get("a"));
    }

    @Test
    void testMapValues() {
        table.put("a", 1);
        table.put("b", 2);
        table.put("c", 3);

        KTable<String, Integer> doubled = table.mapValues(v -> v * 2);

        assertTrue(doubled instanceof InMemoryKTable);
        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) doubled;

        assertEquals(2, result.get("a"));
        assertEquals(4, result.get("b"));
        assertEquals(6, result.get("c"));

        // Original table should be unchanged
        assertEquals(1, table.get("a"));
    }

    @Test
    void testMapValuesWithKey() {
        table.put("a", 1);
        table.put("b", 2);

        KTable<String, String> result = table.mapValues((k, v) -> k + ":" + v);

        assertTrue(result instanceof InMemoryKTable);
        InMemoryKTable<String, String> resultTable = (InMemoryKTable<String, String>) result;

        assertEquals("a:1", resultTable.get("a"));
        assertEquals("b:2", resultTable.get("b"));
    }

    @Test
    void testFilter() {
        table.put("a", 1);
        table.put("b", 2);
        table.put("c", 3);
        table.put("d", 4);

        KTable<String, Integer> filtered = table.filter((k, v) -> v % 2 == 0);

        assertTrue(filtered instanceof InMemoryKTable);
        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) filtered;

        assertEquals(2, result.size());
        assertNull(result.get("a"));
        assertEquals(2, result.get("b"));
        assertNull(result.get("c"));
        assertEquals(4, result.get("d"));
    }

    @Test
    void testJoin() {
        table.put("a", 1);
        table.put("b", 2);
        table.put("c", 3);

        InMemoryKTable<String, String> other = new InMemoryKTable<>();
        other.put("a", "x");
        other.put("b", "y");
        other.put("d", "z");

        KTable<String, String> joined = table.join(other, (v1, v2) -> v1 + "-" + v2);

        assertTrue(joined instanceof InMemoryKTable);
        InMemoryKTable<String, String> result = (InMemoryKTable<String, String>) joined;

        assertEquals(2, result.size());
        assertEquals("1-x", result.get("a"));
        assertEquals("2-y", result.get("b"));
        assertNull(result.get("c")); // No match in other table
        assertNull(result.get("d")); // No match in this table
    }

    @Test
    void testLeftJoin() {
        table.put("a", 1);
        table.put("b", 2);
        table.put("c", 3);

        InMemoryKTable<String, String> other = new InMemoryKTable<>();
        other.put("a", "x");
        other.put("b", "y");

        KTable<String, String> joined = table.leftJoin(other, (v1, v2) ->
                v1 + "-" + (v2 != null ? v2 : "null")
        );

        assertTrue(joined instanceof InMemoryKTable);
        InMemoryKTable<String, String> result = (InMemoryKTable<String, String>) joined;

        assertEquals(3, result.size());
        assertEquals("1-x", result.get("a"));
        assertEquals("2-y", result.get("b"));
        assertEquals("3-null", result.get("c"));
    }

    @Test
    void testGetState() {
        table.put("a", 1);
        table.put("b", 2);

        Map<String, Integer> state = table.getState();

        assertEquals(2, state.size());
        assertEquals(1, state.get("a"));
        assertEquals(2, state.get("b"));

        // Modifying returned state should not affect table
        state.put("c", 3);
        assertNull(table.get("c"));
    }

    @Test
    void testConstructorWithInitialState() {
        Map<String, Integer> initial = new HashMap<>();
        initial.put("a", 1);
        initial.put("b", 2);

        InMemoryKTable<String, Integer> table = new InMemoryKTable<>(initial);

        assertEquals(2, table.size());
        assertEquals(1, table.get("a"));
        assertEquals(2, table.get("b"));
    }

    @Test
    void testGroupBy() {
        table.put("apple", 1);
        table.put("apricot", 2);
        table.put("banana", 3);
        table.put("blueberry", 4);

        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        assertNotNull(grouped);
        assertTrue(grouped instanceof InMemoryKGroupedTable);
    }

    @Test
    void testJoinWithNonInMemoryKTableThrowsException() {
        table.put("a", 1);

        // Create a mock KTable that is not InMemoryKTable
        KTable<String, String> other = new KTable<>() {
            @Override
            public <VR> KTable<String, VR> mapValues(java.util.function.Function<String, VR> mapper) {
                return null;
            }

            @Override
            public <VR> KTable<String, VR> mapValues(java.util.function.BiFunction<String, String, VR> mapper) {
                return null;
            }

            @Override
            public KTable<String, String> filter(java.util.function.BiFunction<String, String, Boolean> predicate) {
                return null;
            }

            @Override
            public <VO, VR> KTable<String, VR> join(KTable<String, VO> other, java.util.function.BiFunction<String, VO, VR> joiner) {
                return null;
            }

            @Override
            public <VO, VR> KTable<String, VR> leftJoin(KTable<String, VO> other, java.util.function.BiFunction<String, VO, VR> joiner) {
                return null;
            }

            @Override
            public io.github.cuihairu.redis.streaming.api.stream.DataStream<KeyValue<String, String>> toStream() {
                return null;
            }

            @Override
            public <KR> KGroupedTable<KR, String> groupBy(java.util.function.Function<KeyValue<String, String>, KR> keySelector) {
                return null;
            }
        };

        assertThrows(UnsupportedOperationException.class, () ->
                table.join(other, (v1, v2) -> v1 + v2)
        );
    }

    @Test
    void testLeftJoinWithNonInMemoryKTableThrowsException() {
        table.put("a", 1);

        KTable<String, String> other = new KTable<>() {
            @Override
            public <VR> KTable<String, VR> mapValues(java.util.function.Function<String, VR> mapper) {
                return null;
            }

            @Override
            public <VR> KTable<String, VR> mapValues(java.util.function.BiFunction<String, String, VR> mapper) {
                return null;
            }

            @Override
            public KTable<String, String> filter(java.util.function.BiFunction<String, String, Boolean> predicate) {
                return null;
            }

            @Override
            public <VO, VR> KTable<String, VR> join(KTable<String, VO> other, java.util.function.BiFunction<String, VO, VR> joiner) {
                return null;
            }

            @Override
            public <VO, VR> KTable<String, VR> leftJoin(KTable<String, VO> other, java.util.function.BiFunction<String, VO, VR> joiner) {
                return null;
            }

            @Override
            public io.github.cuihairu.redis.streaming.api.stream.DataStream<KeyValue<String, String>> toStream() {
                return null;
            }

            @Override
            public <KR> KGroupedTable<KR, String> groupBy(java.util.function.Function<KeyValue<String, String>, KR> keySelector) {
                return null;
            }
        };

        assertThrows(UnsupportedOperationException.class, () ->
                table.leftJoin(other, (v1, v2) -> v1 + v2)
        );
    }

    @Test
    void testPutOverwritesExistingValue() {
        table.put("key", 1);
        assertEquals(1, table.get("key"));

        table.put("key", 2);
        assertEquals(2, table.get("key"));
        assertEquals(1, table.size()); // Still only one entry
    }

    @Test
    void testToString() {
        table.put("a", 1);
        table.put("b", 2);

        String str = table.toString();
        assertTrue(str.contains("InMemoryKTable"));
        assertTrue(str.contains("size=2"));
    }

    @Test
    void testMultiplePutsAndGets() {
        for (int i = 0; i < 100; i++) {
            table.put("key" + i, i);
        }

        assertEquals(100, table.size());

        for (int i = 0; i < 100; i++) {
            assertEquals(i, table.get("key" + i));
        }
    }

    @Test
    void testUpdateValue() {
        table.put("key", 1);
        assertEquals(1, table.get("key"));

        table.put("key", 10);
        assertEquals(10, table.get("key"));
        assertEquals(1, table.size());
    }

    @Test
    void testGetNonExistentKeyReturnsNull() {
        assertNull(table.get("nonexistent"));
    }

    @Test
    void testMapValuesToDifferentType() {
        table.put("a", 1);
        table.put("b", 2);

        KTable<String, String> result = table.mapValues(v -> "value:" + v);

        InMemoryKTable<String, String> mappedTable = (InMemoryKTable<String, String>) result;
        assertEquals("value:1", mappedTable.get("a"));
        assertEquals("value:2", mappedTable.get("b"));
    }

    @Test
    void testFilterWithKeyCondition() {
        table.put("key1", 1);
        table.put("key2", 2);
        table.put("other1", 3);
        table.put("other2", 4);

        KTable<String, Integer> filtered = table.filter((k, v) -> k.startsWith("key"));

        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) filtered;
        assertEquals(2, result.size());
        assertEquals(1, result.get("key1"));
        assertEquals(2, result.get("key2"));
        assertNull(result.get("other1"));
        assertNull(result.get("other2"));
    }

    @Test
    void testFilterReturnsAllWhenPredicateAlwaysTrue() {
        table.put("a", 1);
        table.put("b", 2);

        KTable<String, Integer> filtered = table.filter((k, v) -> true);

        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) filtered;
        assertEquals(2, result.size());
    }

    @Test
    void testFilterReturnsNoneWhenPredicateAlwaysFalse() {
        table.put("a", 1);
        table.put("b", 2);

        KTable<String, Integer> filtered = table.filter((k, v) -> false);

        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) filtered;
        assertEquals(0, result.size());
    }

    @Test
    void testJoinWithEmptyOtherTable() {
        table.put("a", 1);
        table.put("b", 2);

        InMemoryKTable<String, String> other = new InMemoryKTable<>();

        KTable<String, String> joined = table.join(other, (v1, v2) -> v1 + "-" + v2);

        InMemoryKTable<String, String> result = (InMemoryKTable<String, String>) joined;
        assertEquals(0, result.size());
    }

    @Test
    void testJoinWithEmptyThisTable() {
        InMemoryKTable<String, String> other = new InMemoryKTable<>();
        other.put("a", "x");
        other.put("b", "y");

        KTable<String, String> joined = table.join(other, (v1, v2) -> v1 + "-" + v2);

        InMemoryKTable<String, String> result = (InMemoryKTable<String, String>) joined;
        assertEquals(0, result.size());
    }

    @Test
    void testLeftJoinWithEmptyOtherTable() {
        table.put("a", 1);
        table.put("b", 2);

        InMemoryKTable<String, String> other = new InMemoryKTable<>();

        KTable<String, String> joined = table.leftJoin(other, (v1, v2) ->
                v1 + "-" + (v2 != null ? v2 : "null")
        );

        InMemoryKTable<String, String> result = (InMemoryKTable<String, String>) joined;
        assertEquals(2, result.size());
        assertEquals("1-null", result.get("a"));
        assertEquals("2-null", result.get("b"));
    }

    @Test
    void testGetStateReturnsCopy() {
        table.put("a", 1);
        table.put("b", 2);

        Map<String, Integer> state1 = table.getState();
        Map<String, Integer> state2 = table.getState();

        // Both should be independent copies
        assertNotSame(state1, state2);
        assertEquals(state1, state2);

        // Modifying one should not affect the other
        state1.put("c", 3);
        assertNull(state2.get("c"));
    }

    @Test
    void testClearEmptyTable() {
        assertEquals(0, table.size());
        table.clear();
        assertEquals(0, table.size());
    }
}
