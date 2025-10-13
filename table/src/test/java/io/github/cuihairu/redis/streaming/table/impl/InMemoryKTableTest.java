package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
}
