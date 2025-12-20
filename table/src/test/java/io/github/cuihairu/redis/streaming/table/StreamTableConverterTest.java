package io.github.cuihairu.redis.streaming.table;

import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.table.impl.InMemoryKTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}

