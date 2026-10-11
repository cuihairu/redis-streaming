package io.github.cuihairu.redis.streaming.aggregation.incremental;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopKAggregateFunctionTest {

    @Test
    void ranksByCountDescendingThenItemAscending() {
        TopKAggregateFunction<String> f = new TopKAggregateFunction<>(10);
        AggregateFunction.Accumulator<String> acc = f.createAccumulator();
        for (String item : new String[]{"b", "a", "b", "c", "a", "b"}) {
            acc = f.add(item, acc);
        }
        // b has 3 hits, a and c tie at 1 -> a ranks before c by name
        List<TopKAggregateFunction.TopKEntry<String>> top = f.getResult(
                (TopKAggregateFunction.TopKAccumulator<String>) acc);
        assertEquals(3, top.size());
        assertEquals("b", top.get(0).item());
        assertEquals(3L, top.get(0).count());
        assertEquals("a", top.get(1).item());
        assertEquals("c", top.get(2).item());
    }

    @Test
    void limitsResultToK() {
        TopKAggregateFunction<String> f = new TopKAggregateFunction<>(2);
        TopKAggregateFunction.TopKAccumulator<String> acc = f.createAccumulator();
        for (String item : new String[]{"a", "b", "c", "d"}) {
            acc = f.add(item, acc);
        }
        List<TopKAggregateFunction.TopKEntry<String>> top = f.getResult(acc);
        assertEquals(2, top.size());
        assertEquals("a", top.get(0).item());
        assertEquals("b", top.get(1).item());
    }

    @Test
    void emptyAccumulatorYieldsEmptyResult() {
        TopKAggregateFunction<String> f = new TopKAggregateFunction<>(5);
        assertTrue(f.getResult(f.createAccumulator()).isEmpty());
    }

    @Test
    void mergeSumsCountsAcrossAccumulators() {
        TopKAggregateFunction<String> f = new TopKAggregateFunction<>(10);
        TopKAggregateFunction.TopKAccumulator<String> left = f.createAccumulator();
        TopKAggregateFunction.TopKAccumulator<String> right = f.createAccumulator();
        left = f.add("x", left);
        left = f.add("x", left);
        right = f.add("x", right);
        right = f.add("y", right);
        TopKAggregateFunction.TopKAccumulator<String> merged = f.merge(left, right);
        List<TopKAggregateFunction.TopKEntry<String>> top = f.getResult(merged);
        assertEquals(2, top.size());
        assertEquals("x", top.get(0).item());
        assertEquals(3L, top.get(0).count());
        assertEquals("y", top.get(1).item());
    }

    @Test
    void rejectsNullValueAndNonPositiveK() {
        TopKAggregateFunction<String> f = new TopKAggregateFunction<>(3);
        assertThrows(NullPointerException.class, () -> f.add(null, f.createAccumulator()));
        assertThrows(IllegalArgumentException.class, () -> new TopKAggregateFunction<>(0));
        assertThrows(IllegalArgumentException.class, () -> new TopKAggregateFunction<>(-1));
    }

    @Test
    void survivesSerializationRoundTrip() throws Exception {
        TopKAggregateFunction<String> f = new TopKAggregateFunction<>(10);
        TopKAggregateFunction.TopKAccumulator<String> acc = f.add("z", f.createAccumulator());
        acc = f.add("z", acc);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(f);
            out.writeObject(acc);
        }
        TopKAggregateFunction<String> restoredFunction;
        TopKAggregateFunction.TopKAccumulator<String> restoredAccumulator;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restoredFunction = (TopKAggregateFunction<String>) in.readObject();
            restoredAccumulator = (TopKAggregateFunction.TopKAccumulator<String>) in.readObject();
        }
        assertEquals(10, restoredFunction.getK());
        List<TopKAggregateFunction.TopKEntry<String>> top = restoredFunction.getResult(restoredAccumulator);
        assertEquals(1, top.size());
        assertEquals("z", top.get(0).item());
        assertEquals(2L, top.get(0).count());
    }
}
