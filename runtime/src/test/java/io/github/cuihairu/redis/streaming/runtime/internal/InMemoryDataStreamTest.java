package io.github.cuihairu.redis.streaming.runtime.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryDataStreamTest {

    @Test
    void filterHasNextIsIdempotentUntilNextCalled() {
        AtomicInteger predicateCalls = new AtomicInteger();
        InMemoryDataStream<Integer> stream = new InMemoryDataStream<>(() -> List.of(1, 2, 3).iterator());

        @SuppressWarnings("unchecked")
        InMemoryDataStream<Integer> filtered = (InMemoryDataStream<Integer>) stream.filter(v -> {
            predicateCalls.incrementAndGet();
            return v % 2 == 1;
        });
        Iterator<Integer> it = filtered.iterator();

        assertTrue(it.hasNext());
        assertTrue(it.hasNext());
        assertEquals(1, predicateCalls.get());
        assertEquals(1, it.next());

        assertTrue(it.hasNext());
        assertEquals(3, it.next());
        assertFalse(it.hasNext());
    }

    @Test
    void mapFilterFlatMapPipelineWorks() {
        InMemoryDataStream<String> stream = new InMemoryDataStream<>(() -> List.of("a", "", "b").iterator());

        List<String> out = new ArrayList<>();
        stream
                .filter(s -> !s.isBlank())
                .flatMap(s -> List.of(s, s))
                .map(String::toUpperCase)
                .addSink(out::add);

        assertEquals(List.of("A", "A", "B", "B"), out);
    }

    @Test
    void flatMapTreatsNullAsEmptyIterable() {
        InMemoryDataStream<Integer> stream = new InMemoryDataStream<>(() -> List.of(1, 2, 3).iterator());

        List<Integer> out = new ArrayList<>();
        stream.flatMap(v -> v == 2 ? null : List.of(v)).addSink(out::add);

        assertEquals(List.of(1, 3), out);
    }

    @Test
    void addSinkWrapsExceptions() {
        InMemoryDataStream<Integer> stream = new InMemoryDataStream<>(() -> List.of(1).iterator());

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                stream.addSink(v -> {
                    throw new Exception("boom");
                }));

        assertEquals("Sink invocation failed", ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    void nullOperatorArgumentsFailFast() {
        InMemoryDataStream<Integer> stream = new InMemoryDataStream<>(List.<Integer>of()::iterator);

        assertThrows(NullPointerException.class, () -> stream.map(null));
        assertThrows(NullPointerException.class, () -> stream.filter(null));
        assertThrows(NullPointerException.class, () -> stream.flatMap(null));
    }

    @Test
    void iteratorNextThrowsWhenExhausted() {
        InMemoryDataStream<Integer> stream = new InMemoryDataStream<>(List.<Integer>of()::iterator);
        @SuppressWarnings("unchecked")
        InMemoryDataStream<Integer> filtered = (InMemoryDataStream<Integer>) stream.filter(v -> true);
        Iterator<Integer> it = filtered.iterator();

        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }
}
