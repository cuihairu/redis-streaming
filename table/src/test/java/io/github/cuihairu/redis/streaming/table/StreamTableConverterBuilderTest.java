package io.github.cuihairu.redis.streaming.table;

import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.table.impl.InMemoryKTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class StreamTableConverterBuilderTest {

    @Test
    void toTableOverloadWithExtractorsBuildsKTableState() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KTable<String, Integer> table = StreamTableConverter.toTable(
                env.fromElements("a=1", "b=2", "a=3", "b="),
                s -> s.split("=", 2)[0],
                s -> {
                    String[] parts = s.split("=", 2);
                    if (parts.length < 2 || parts[1].isEmpty()) {
                        return null; // delete
                    }
                    return Integer.parseInt(parts[1]);
                }
        );

        assertInstanceOf(InMemoryKTable.class, table);
        InMemoryKTable<String, Integer> t = (InMemoryKTable<String, Integer>) table;
        assertEquals(1, t.size());
        assertEquals(3, t.get("a"));
        assertNull(t.get("b"));
    }

    @Test
    void tableBuilderBuildDelegatesToToTable() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        StreamTableConverter.TableBuilder<String, String, Integer> builder =
                StreamTableConverter.tableBuilder(
                        s -> s.split(":", 2)[0],
                        s -> Integer.parseInt(s.split(":", 2)[1])
                );

        KTable<String, Integer> table = builder.build(env.fromElements("k:10", "k:11", "x:1"));
        assertInstanceOf(InMemoryKTable.class, table);
        InMemoryKTable<String, Integer> t = (InMemoryKTable<String, Integer>) table;
        assertEquals(2, t.size());
        assertEquals(11, t.get("k"));
        assertEquals(1, t.get("x"));
    }
}

