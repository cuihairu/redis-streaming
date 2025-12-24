package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryKeyedStreamTest {

    @Test
    void reduceAccumulatesPerKeyAndEmitsPerRecord() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1, 2, 3).iterator(),
                v -> v % 2 == 0 ? "even" : "odd"
        );

        List<Integer> out = new ArrayList<>();
        keyed.reduce((a, b) -> a + b).addSink(out::add);

        assertEquals(List.of(1, 2, 4), out);
    }

    @Test
    void reduceWrapsCheckedExceptions() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1, 2).iterator(),
                v -> "k"
        );

        ReduceFunction<Integer> reducer = (a, b) -> {
            throw new Exception("boom");
        };

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                keyed.reduce(reducer).addSink(v -> {
                }));
        assertEquals("Reduce function failed", ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    void processWrapsCheckedExceptions() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1).iterator(),
                v -> "k"
        );

        KeyedProcessFunction<String, Integer, String> fn = (key, value, ctx, out) -> {
            throw new Exception("boom");
        };

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                keyed.process(fn).addSink(v -> {
                }));
        assertEquals("Keyed process function failed", ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    void nullArgumentsFailFast() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1).iterator(),
                v -> "k"
        );

        assertThrows(NullPointerException.class, () -> keyed.reduce(null));
        assertThrows(NullPointerException.class, () -> keyed.process(null));
        assertThrows(NullPointerException.class, () -> keyed.map(null));
    }
}

