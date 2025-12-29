package io.github.cuihairu.redis.streaming.table;

import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.table.impl.InMemoryKTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamTableConverterTest {

    @Test
    void toTableConsumesStreamAndBuildsInMemoryState() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<String, Integer> table = StreamTableConverter.toTable(
                env.fromElements(
                        KTable.KeyValue.of("a", 1),
                        KTable.KeyValue.of("a", 2),
                        KTable.KeyValue.of("b", 3),
                        KTable.KeyValue.of("a", null)
                )
        );

        assertTrue(table instanceof InMemoryKTable);
        InMemoryKTable<String, Integer> t = (InMemoryKTable<String, Integer>) table;
        assertEquals(1, t.size());
        assertEquals(3, t.get("b"));
        assertEquals(null, t.get("a"));
    }

    @Test
    void inMemoryKTableToStreamIsUsableWithRuntimeOperators() {
        InMemoryKTable<String, Integer> table = new InMemoryKTable<>();
        table.put("a", 1);
        table.put("b", 2);

        List<String> out = new ArrayList<>();
        table.toStream()
                .map(kv -> kv.getKey() + "=" + kv.getValue())
                .addSink(out::add);

        Collections.sort(out);
        assertEquals(List.of("a=1", "b=2"), out);
    }

    @Test
    void toTableWithEmptyStream() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<String, Integer> table = StreamTableConverter.toTable(
                env.fromElements()
        );

        assertTrue(table instanceof InMemoryKTable);
        InMemoryKTable<String, Integer> t = (InMemoryKTable<String, Integer>) table;
        assertEquals(0, t.size());
    }

    @Test
    void toTableWithSingleElement() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<String, Integer> table = StreamTableConverter.toTable(
                env.fromElements(KTable.KeyValue.of("key", 42))
        );

        InMemoryKTable<String, Integer> t = (InMemoryKTable<String, Integer>) table;
        assertEquals(1, t.size());
        assertEquals(42, t.get("key"));
    }

    @Test
    void toTableWithMultipleUpdatesToSameKey() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<String, Integer> table = StreamTableConverter.toTable(
                env.fromElements(
                        KTable.KeyValue.of("x", 1),
                        KTable.KeyValue.of("x", 2),
                        KTable.KeyValue.of("x", 3),
                        KTable.KeyValue.of("x", 4)
                )
        );

        InMemoryKTable<String, Integer> t = (InMemoryKTable<String, Integer>) table;
        assertEquals(1, t.size());
        assertEquals(4, t.get("x")); // Last value wins
    }

    @Test
    void toTableWithNullValueDeletesKey() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<String, Integer> table = StreamTableConverter.toTable(
                env.fromElements(
                        KTable.KeyValue.of("a", 1),
                        KTable.KeyValue.of("b", 2),
                        KTable.KeyValue.of("c", 3),
                        KTable.KeyValue.of("b", null) // Delete b
                )
        );

        InMemoryKTable<String, Integer> t = (InMemoryKTable<String, Integer>) table;
        assertEquals(2, t.size());
        assertEquals(1, t.get("a"));
        assertEquals(3, t.get("c"));
        assertNull(t.get("b"));
    }

    @Test
    void toTableWithManyKeys() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<String, Integer> table = StreamTableConverter.toTable(
                env.fromElements(
                        KTable.KeyValue.of("k1", 1),
                        KTable.KeyValue.of("k2", 2),
                        KTable.KeyValue.of("k3", 3),
                        KTable.KeyValue.of("k4", 4),
                        KTable.KeyValue.of("k5", 5)
                )
        );

        InMemoryKTable<String, Integer> t = (InMemoryKTable<String, Integer>) table;
        assertEquals(5, t.size());
        assertEquals(1, t.get("k1"));
        assertEquals(2, t.get("k2"));
        assertEquals(3, t.get("k3"));
        assertEquals(4, t.get("k4"));
        assertEquals(5, t.get("k5"));
    }

    @Test
    void toTableWithComplexValueType() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<String, List<String>> table = StreamTableConverter.toTable(
                env.fromElements(
                        KTable.KeyValue.of("list1", List.of("a", "b")),
                        KTable.KeyValue.of("list2", List.of("c", "d"))
                )
        );

        InMemoryKTable<String, List<String>> t = (InMemoryKTable<String, List<String>>) table;
        assertEquals(2, t.size());
        assertEquals(List.of("a", "b"), t.get("list1"));
        assertEquals(List.of("c", "d"), t.get("list2"));
    }

    @Test
    void toStreamWithEmptyTable() {
        InMemoryKTable<String, Integer> table = new InMemoryKTable<>();

        List<String> out = new ArrayList<>();
        table.toStream()
                .map(kv -> kv.getKey() + "=" + kv.getValue())
                .addSink(out::add);

        assertEquals(0, out.size());
    }

    @Test
    void toStreamWithSingleEntry() {
        InMemoryKTable<String, Integer> table = new InMemoryKTable<>();
        table.put("only", 100);

        List<String> out = new ArrayList<>();
        table.toStream()
                .map(kv -> kv.getKey() + "=" + kv.getValue())
                .addSink(out::add);

        assertEquals(List.of("only=100"), out);
    }

    @Test
    void toStreamWithManyEntries() {
        InMemoryKTable<String, Integer> table = new InMemoryKTable<>();
        for (int i = 0; i < 100; i++) {
            table.put("key" + i, i);
        }

        List<String> out = new ArrayList<>();
        table.toStream()
                .map(kv -> kv.getKey() + "=" + kv.getValue())
                .addSink(out::add);

        assertEquals(100, out.size());
    }

    @Test
    void toStreamPreservesKeyValueTypes() {
        InMemoryKTable<Integer, String> table = new InMemoryKTable<>();
        table.put(1, "one");
        table.put(2, "two");

        List<String> out = new ArrayList<>();
        table.toStream()
                .map(kv -> kv.getKey() + ":" + kv.getValue())
                .addSink(out::add);

        Collections.sort(out);
        assertEquals(List.of("1:one", "2:two"), out);
    }

    @Test
    void toStreamCanChainMultipleOperations() {
        InMemoryKTable<String, Integer> table = new InMemoryKTable<>();
        table.put("a", 1);
        table.put("b", 2);
        table.put("c", 3);

        List<String> out = new ArrayList<>();
        table.toStream()
                .filter(kv -> kv.getValue() > 1)
                .map(kv -> kv.getKey().toUpperCase())
                .addSink(out::add);

        Collections.sort(out);
        assertEquals(List.of("B", "C"), out);
    }

    @Test
    void toTableThenToStreamRoundTrip() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Create table from stream
        KTable<String, Integer> table = StreamTableConverter.toTable(
                env.fromElements(
                        KTable.KeyValue.of("a", 1),
                        KTable.KeyValue.of("b", 2),
                        KTable.KeyValue.of("c", 3)
                )
        );

        // Convert back to stream
        List<String> out = new ArrayList<>();
        table.toStream()
                .map(kv -> kv.getKey() + "=" + kv.getValue())
                .addSink(out::add);

        Collections.sort(out);
        assertEquals(List.of("a=1", "b=2", "c=3"), out);
    }

    @Test
    void toTableWithDuplicateKeys() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<String, Integer> table = StreamTableConverter.toTable(
                env.fromElements(
                        KTable.KeyValue.of("duplicate", 1),
                        KTable.KeyValue.of("duplicate", 2),
                        KTable.KeyValue.of("duplicate", 3)
                )
        );

        InMemoryKTable<String, Integer> t = (InMemoryKTable<String, Integer>) table;
        assertEquals(1, t.size());
        assertEquals(3, t.get("duplicate")); // Last update wins
    }

    @Test
    void toStreamWithNullValues() {
        InMemoryKTable<String, String> table = new InMemoryKTable<>();
        table.put("hasNull", null);
        table.put("hasValue", "value");

        List<String> out = new ArrayList<>();
        table.toStream()
                .filter(kv -> kv.getValue() != null)
                .map(kv -> kv.getKey() + "=" + kv.getValue())
                .addSink(out::add);

        assertEquals(List.of("hasValue=value"), out);
    }

    @Test
    void toTableWithIntegerKeys() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<Integer, String> table = StreamTableConverter.toTable(
                env.fromElements(
                        KTable.KeyValue.of(1, "one"),
                        KTable.KeyValue.of(2, "two"),
                        KTable.KeyValue.of(3, "three")
                )
        );

        InMemoryKTable<Integer, String> t = (InMemoryKTable<Integer, String>) table;
        assertEquals(3, t.size());
        assertEquals("one", t.get(1));
        assertEquals("two", t.get(2));
        assertEquals("three", t.get(3));
    }

    @Test
    void toStreamWithSpecialCharacters() {
        InMemoryKTable<String, String> table = new InMemoryKTable<>();
        table.put("key:with:colons", "value");
        table.put("key-with-dashes", "value2");

        List<String> out = new ArrayList<>();
        table.toStream()
                .map(kv -> kv.getKey() + "=" + kv.getValue())
                .addSink(out::add);

        Collections.sort(out);
        assertEquals(2, out.size());
        assertTrue(out.contains("key:with:colons=value"));
        assertTrue(out.contains("key-with-dashes=value2"));
    }
}

