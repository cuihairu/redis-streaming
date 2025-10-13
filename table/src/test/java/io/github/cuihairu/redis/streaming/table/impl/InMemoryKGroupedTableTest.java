package io.github.cuihairu.redis.streaming.table.impl;

import io.github.cuihairu.redis.streaming.table.KTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryKGroupedTableTest {

    private InMemoryKTable<String, Integer> table;

    @BeforeEach
    void setUp() {
        table = new InMemoryKTable<>();
        table.put("apple", 1);
        table.put("apricot", 2);
        table.put("banana", 3);
        table.put("blueberry", 4);
        table.put("cherry", 5);
    }

    @Test
    void testCount() {
        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        KTable<String, Long> counts = grouped.count();

        assertTrue(counts instanceof InMemoryKTable);
        InMemoryKTable<String, Long> result = (InMemoryKTable<String, Long>) counts;

        assertEquals(3, result.size());
        assertEquals(2L, result.get("a")); // apple, apricot
        assertEquals(2L, result.get("b")); // banana, blueberry
        assertEquals(1L, result.get("c")); // cherry
    }

    @Test
    void testReduce() {
        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        KTable<String, Integer> reduced = grouped.reduce(
                Integer::sum,  // adder
                (a, b) -> a - b  // subtractor (not used in this test)
        );

        assertTrue(reduced instanceof InMemoryKTable);
        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) reduced;

        assertEquals(3, result.size());
        assertEquals(3, result.get("a")); // 1 + 2
        assertEquals(7, result.get("b")); // 3 + 4
        assertEquals(5, result.get("c")); // 5
    }

    @Test
    void testAggregate() {
        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        // Aggregate by concatenating keys
        KTable<String, String> aggregated = grouped.aggregate(
                () -> "",  // initializer
                (key, value) -> key + ":" + value,  // adder (simplified)
                (key, value) -> key  // subtractor (not properly used here)
        );

        assertTrue(aggregated instanceof InMemoryKTable);
        InMemoryKTable<String, String> result = (InMemoryKTable<String, String>) aggregated;

        assertEquals(3, result.size());
        // The aggregation should have produced some result for each group
        assertNotNull(result.get("a"));
        assertNotNull(result.get("b"));
        assertNotNull(result.get("c"));
    }

    @Test
    void testGroupByWithDifferentKeyTypes() {
        // Group by string length
        var grouped = table.groupBy(kv ->
                kv.getKey().length()
        );

        KTable<Integer, Long> counts = grouped.count();

        assertTrue(counts instanceof InMemoryKTable);
        InMemoryKTable<Integer, Long> result = (InMemoryKTable<Integer, Long>) counts;

        // apple(5), banana(6), cherry(6), apricot(7), blueberry(9)
        assertEquals(1L, result.get(5));  // apple
        assertEquals(2L, result.get(6));  // banana, cherry
        assertEquals(1L, result.get(7));  // apricot
        assertEquals(1L, result.get(9));  // blueberry
    }

    @Test
    void testEmptyTable() {
        InMemoryKTable<String, Integer> emptyTable = new InMemoryKTable<>();
        var grouped = emptyTable.groupBy(kv -> kv.getKey());

        KTable<String, Long> counts = grouped.count();

        assertTrue(counts instanceof InMemoryKTable);
        InMemoryKTable<String, Long> result = (InMemoryKTable<String, Long>) counts;

        assertEquals(0, result.size());
    }
}
