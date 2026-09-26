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
                (key, value, agg) -> agg + key + ":" + value,  // adder folds into the aggregate
                (key, value, agg) -> key  // subtractor (not used for batch tables)
        );

        assertTrue(aggregated instanceof InMemoryKTable);
        InMemoryKTable<String, String> result = (InMemoryKTable<String, String>) aggregated;

        assertEquals(3, result.size());
        // Every record of the group must be folded in, not just the last one.
        assertTrue(result.get("a").contains("a:1"));
        assertTrue(result.get("a").contains("a:2"));
        assertTrue(result.get("b").contains("b:3"));
        assertTrue(result.get("b").contains("b:4"));
        assertTrue(result.get("c").contains("c:5"));
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

    @Test
    void testReduceWithSubtractor() {
        table.put("apple", 10); // Update existing
        table.put("banana", 20);

        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        KTable<String, Integer> reduced = grouped.reduce(
                Integer::sum,
                Integer::sum  // subtractor - for testing, use same logic
        );

        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) reduced;

        // a: 1 + 2 + 10 = 13, b: 3 + 4 + 20 = 27, c: 5
        assertEquals(3, result.size());
    }

    @Test
    void testReduceWithEmptyGroup() {
        InMemoryKTable<String, Integer> singleTable = new InMemoryKTable<>();
        singleTable.put("only", 1);

        var grouped = singleTable.groupBy(kv -> kv.getKey().substring(0, 1));

        KTable<String, Integer> reduced = grouped.reduce(Integer::sum, (a, b) -> a);

        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) reduced;

        assertEquals(1, result.size());
        assertEquals(1, result.get("o"));
    }

    @Test
    void testAggregateWithComplexInitializer() {
        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        // Aggregate to sum values (most common use case)
        KTable<String, Integer> aggregated = grouped.aggregate(
                () -> 0,
                (key, value, agg) -> value + agg,
                (key, value, agg) -> agg - value
        );

        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) aggregated;

        assertEquals(3, result.size());
        assertEquals(3, result.get("a")); // 1 + 2
        assertEquals(7, result.get("b")); // 3 + 4
        assertEquals(5, result.get("c")); // 5
    }

    @Test
    void testCountWithSingleElementGroups() {
        InMemoryKTable<String, Integer> singleTable = new InMemoryKTable<>();
        singleTable.put("a1", 1);
        singleTable.put("b1", 2);
        singleTable.put("c1", 3);

        var grouped = singleTable.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        KTable<String, Long> counts = grouped.count();

        InMemoryKTable<String, Long> result = (InMemoryKTable<String, Long>) counts;

        assertEquals(3, result.size());
        assertEquals(1L, result.get("a"));
        assertEquals(1L, result.get("b"));
        assertEquals(1L, result.get("c"));
    }

    @Test
    void testGroupByWithValueSelector() {
        table.put("apple", 100);
        table.put("apricot", 200);

        // Group by even/odd values
        var grouped = table.groupBy(kv ->
                kv.getValue() % 2 == 0 ? "even" : "odd"
        );

        KTable<String, Long> counts = grouped.count();

        InMemoryKTable<String, Long> result = (InMemoryKTable<String, Long>) counts;

        // Note: apple and apricot values were updated from 1->100 and 2->200
        // So now we have: apple(100, even), apricot(200, even), banana(3, odd), blueberry(4, even), cherry(5, odd)
        assertEquals(2, result.size());
        assertEquals(3L, result.get("even")); // 100, 200, 4
        assertEquals(2L, result.get("odd"));  // 3, 5
    }

    @Test
    void testGroupByWithNullValues() {
        InMemoryKTable<String, Integer> tableWithNulls = new InMemoryKTable<>();
        tableWithNulls.put("a1", 1);
        tableWithNulls.put("a2", null);
        tableWithNulls.put("b1", 2);

        var grouped = tableWithNulls.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        KTable<String, Long> counts = grouped.count();

        InMemoryKTable<String, Long> result = (InMemoryKTable<String, Long>) counts;

        // Note: null values are handled by the table implementation
        // This test verifies the grouping behavior
        assertTrue(result.size() >= 1);
    }

    @Test
    void testAggregateWithNumericResult() {
        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        // Aggregate to sum values: the adder must fold every value into the running aggregate
        // (regression for B-03: the old 2-arg adder silently returned only the last value).
        KTable<String, Integer> sum = grouped.aggregate(
                () -> 0,
                (key, value, agg) -> value + agg,
                (key, value, agg) -> agg - value
        );

        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) sum;

        assertEquals(3, result.size());
        assertEquals(3, result.get("a")); // 1 + 2
        assertEquals(7, result.get("b")); // 3 + 4
        assertEquals(5, result.get("c")); // 5
    }

    @Test
    void testMultipleGroupingOperations() {
        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        // Perform multiple operations on same grouped table
        KTable<String, Long> counts = grouped.count();
        KTable<String, Integer> reduced = grouped.reduce(Integer::sum, (a, b) -> a);

        InMemoryKTable<String, Long> countResult = (InMemoryKTable<String, Long>) counts;
        InMemoryKTable<String, Integer> reducedResult = (InMemoryKTable<String, Integer>) reduced;

        assertEquals(3, countResult.size());
        assertEquals(3, reducedResult.size());
    }

    @Test
    void testGroupByWithComplexKeySelector() {
        table.put("alpha", 1);
        table.put("beta", 2);
        table.put("gamma", 3);

        // Group by first and last character
        var grouped = table.groupBy(kv -> {
            String key = kv.getKey();
            return "" + key.charAt(0) + key.charAt(key.length() - 1);
        });

        KTable<String, Long> counts = grouped.count();

        InMemoryKTable<String, Long> result = (InMemoryKTable<String, Long>) counts;

        // aa: alpha, ba: banana, ba: blueberry, bt: beta, cc: cherry, ae: apricot
        assertTrue(result.size() > 0);
    }

    @Test
    void testReduceWithAllSameGroup() {
        InMemoryKTable<String, Integer> sameGroupTable = new InMemoryKTable<>();
        sameGroupTable.put("a1", 1);
        sameGroupTable.put("a2", 2);
        sameGroupTable.put("a3", 3);

        var grouped = sameGroupTable.groupBy(kv -> "all");

        KTable<String, Integer> reduced = grouped.reduce(Integer::sum, (a, b) -> a);

        InMemoryKTable<String, Integer> result = (InMemoryKTable<String, Integer>) reduced;

        assertEquals(1, result.size());
        assertEquals(6, result.get("all")); // 1 + 2 + 3
    }

    @Test
    void testAggregateProducesDistinctResults() {
        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        KTable<String, Integer> aggregated1 = grouped.aggregate(
                () -> 0,
                (key, value, agg) -> value + agg,
                (key, value, agg) -> agg - value
        );

        KTable<String, Long> counts = grouped.count();

        // Different aggregations should produce results
        InMemoryKTable<String, Integer> aggResult = (InMemoryKTable<String, Integer>) aggregated1;
        InMemoryKTable<String, Long> countResult = (InMemoryKTable<String, Long>) counts;

        assertEquals(aggResult.size(), countResult.size());
    }

    @Test
    void testGroupByPreservesAllData() {
        var grouped = table.groupBy(kv ->
                kv.getKey().substring(0, 1)
        );

        KTable<String, Long> counts = grouped.count();
        InMemoryKTable<String, Long> result = (InMemoryKTable<String, Long>) counts;

        // Total count should match original table size
        long totalCount = 0;
        for (String key : result.getState().keySet()) {
            totalCount += result.get(key);
        }
        assertEquals(5L, totalCount); // 5 original entries
    }

    @Test
    void nullGroupKeyRowsAreSkippedLikeRedisImplementation() {
        // Regression for B-17: a null group key used to NPE in count/aggregate/reduce while the
        // Redis implementation silently skipped the row; the two implementations must agree.
        InMemoryKTable<String, Integer> mixed = new InMemoryKTable<>();
        mixed.put("apple", 1);
        mixed.put("banana", 3);

        var grouped = mixed.groupBy(kv -> "apple".equals(kv.getKey()) ? null : "b");

        InMemoryKTable<String, Long> counts = (InMemoryKTable<String, Long>) grouped.count();
        assertEquals(1, counts.size());
        assertEquals(1L, counts.get("b"));

        InMemoryKTable<String, Integer> summed = (InMemoryKTable<String, Integer>) grouped.aggregate(
                () -> 0, (key, value, agg) -> value + agg, (key, value, agg) -> agg - value);
        assertEquals(1, summed.size());
        assertEquals(3, summed.get("b"));

        InMemoryKTable<String, Integer> reduced = (InMemoryKTable<String, Integer>) grouped.reduce(Integer::sum, (a, b) -> a);
        assertEquals(1, reduced.size());
        assertEquals(3, reduced.get("b"));
    }
}
