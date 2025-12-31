package io.github.cuihairu.redis.streaming.runtime;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamExecutionEnvironmentTest {

    @Test
    void fromElementsSupportsBasicOperators() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<String> out = new ArrayList<>();
        env.fromElements("a b", "c", "d e")
                .flatMap(line -> Arrays.asList(line.split(" ")))
                .filter(word -> !"c".equals(word))
                .map(String::toUpperCase)
                .addSink(out::add);

        assertEquals(List.of("A", "B", "D", "E"), out);
    }

    @Test
    void keyedProcessValueStateIsIsolatedByKey() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        StateDescriptor<Integer> descriptor = new StateDescriptor<>("count", Integer.class, 0);
        KeyedStream<String, String> keyed = env.fromElements("a", "b", "a").keyBy(v -> v);
        ValueState<Integer> count = keyed.getState(descriptor);

        List<String> out = new ArrayList<>();
        keyed.<String>process((key, value, ctx, collector) -> {
                    int next = count.value() + 1;
                    count.update(next);
                    collector.collect(key + ":" + next);
                })
                .addSink(out::add);

        assertEquals(List.of("a:1", "b:1", "a:2"), out);
    }

    @Test
    void addSourceCollectsElements() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        StreamSource<Integer> source = ctx -> {
            ctx.collect(1);
            ctx.collectWithTimestamp(2, 123L);
            ctx.collect(3);
        };

        List<Integer> out = new ArrayList<>();
        env.addSource(source).addSink(out::add);

        assertEquals(List.of(1, 2, 3), out);
    }

    @Test
    void fromElementsWithSingleElement() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<String> out = new ArrayList<>();
        env.fromElements("hello").addSink(out::add);

        assertEquals(List.of("hello"), out);
    }

    @Test
    void fromCollectionWithList() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<Integer> out = new ArrayList<>();
        env.fromCollection(Arrays.asList(1, 2, 3)).addSink(out::add);

        assertEquals(Arrays.asList(1, 2, 3), out);
    }

    @Test
    void filterOperator() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<Integer> out = new ArrayList<>();
        env.fromElements(1, 2, 3, 4, 5)
                .filter(x -> x % 2 == 0)
                .addSink(out::add);

        assertEquals(Arrays.asList(2, 4), out);
    }

    @Test
    void mapOperator() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<Integer> out = new ArrayList<>();
        env.fromElements(1, 2, 3)
                .map(x -> x * 2)
                .addSink(out::add);

        assertEquals(Arrays.asList(2, 4, 6), out);
    }

    @Test
    void flatMapOperator() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<String> out = new ArrayList<>();
        env.fromElements("hello", "world")
                .flatMap(s -> Arrays.asList(s.split("")))
                .addSink(out::add);

        assertEquals(Arrays.asList("h", "e", "l", "l", "o", "w", "o", "r", "l", "d"), out);
    }

    @Test
    void chainedOperators() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<Integer> out = new ArrayList<>();
        env.fromElements(1, 2, 3, 4, 5)
                .filter(x -> x > 2)
                .map(x -> x * 10)
                .addSink(out::add);

        assertEquals(Arrays.asList(30, 40, 50), out);
    }

    @Test
    void printSink() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Should not throw
        assertDoesNotThrow(() -> env.fromElements("a", "b", "c").print());
    }

    @Test
    void keyByWithProcess() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<String> out = new ArrayList<>();
        env.fromElements("a1", "a2", "b1", "b2")
                .keyBy(s -> s.substring(0, 1))
                .<String>process((key, value, ctx, collector) -> {
                    collector.collect(key + "=" + value);
                })
                .addSink(out::add);

        assertEquals(Arrays.asList("a=a1", "a=a2", "b=b1", "b=b2"), out);
    }

    @Test
    void multipleSinks() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<Integer> out1 = new ArrayList<>();
        List<Integer> out2 = new ArrayList<>();

        var stream = env.fromElements(1, 2, 3);
        stream.addSink(out1::add);
        stream.addSink(out2::add);

        assertEquals(Arrays.asList(1, 2, 3), out1);
        assertEquals(Arrays.asList(1, 2, 3), out2);
    }

    @Test
    void enableCheckpointing() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        assertNull(env.getCheckpointCoordinator());

        env.enableCheckpointing();

        assertNotNull(env.getCheckpointCoordinator());
    }

    @Test
    void enableCheckpointingMultipleTimes() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        env.enableCheckpointing();
        var coordinator1 = env.getCheckpointCoordinator();

        env.enableCheckpointing();
        var coordinator2 = env.getCheckpointCoordinator();

        // Should return the same coordinator
        assertSame(coordinator1, coordinator2);
    }

    @Test
    void fromElementsWithNull() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        assertThrows(NullPointerException.class, () -> env.fromElements((String[]) null));
    }

    @Test
    void fromCollectionWithNull() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        assertThrows(NullPointerException.class, () -> env.fromCollection(null));
    }

    @Test
    void addSourceWithNull() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        assertThrows(NullPointerException.class, () -> env.addSource(null));
    }

    @Test
    void sourceWithException() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        StreamSource<Integer> source = ctx -> {
            throw new RuntimeException("Test exception");
        };

        assertThrows(RuntimeException.class, () -> env.addSource(source));
    }

    @Test
    void filterAllOut() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<Integer> out = new ArrayList<>();
        env.fromElements(1, 2, 3)
                .filter(x -> x > 10)
                .addSink(out::add);

        assertTrue(out.isEmpty());
    }

    @Test
    void mapIdentity() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<String> out = new ArrayList<>();
        env.fromElements("a", "b", "c")
                .map(x -> x)
                .addSink(out::add);

        assertEquals(Arrays.asList("a", "b", "c"), out);
    }

    @Test
    void sourceWithTimestamps() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        StreamSource<String> source = ctx -> {
            ctx.collectWithTimestamp("first", 100L);
            ctx.collectWithTimestamp("second", 200L);
        };

        List<String> out = new ArrayList<>();
        env.addSource(source).addSink(out::add);

        assertEquals(Arrays.asList("first", "second"), out);
    }
}
