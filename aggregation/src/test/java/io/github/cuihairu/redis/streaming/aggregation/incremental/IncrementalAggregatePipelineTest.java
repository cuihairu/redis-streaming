package io.github.cuihairu.redis.streaming.aggregation.incremental;

import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;
import io.github.cuihairu.redis.streaming.runtime.internal.InMemoryKeyedStream;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unification regression: the incremental AggregateFunction implementations feed
 * {@code WindowedStream.aggregate} — the pipeline entry point the batch
 * {@code AggregationFunction} interface never fit.
 */
class IncrementalAggregatePipelineTest {

    private static List<String> events() {
        return List.of("a", "b", "a", "c", "b");
    }

    /** Public InMemory entry; timestamps increment per record so all records share one window. */
    private static WindowedStream<String, String> windowed() {
        KeyedStream<String, String> keyed = new InMemoryKeyedStream<>(events()::iterator, r -> "user");
        return keyed.window(TumblingWindow.ofMillis(1000));
    }

    @Test
    void uvCountsDistinctRecordsInAWindow() {
        List<Long> results = new ArrayList<>();
        windowed().aggregate(new UVAggregateFunction<>()).addSink(results::add);
        assertEquals(1, results.size());
        assertEquals(3L, results.get(0));
    }

    @Test
    void pvCountsAllRecordsInAWindow() {
        List<Long> results = new ArrayList<>();
        windowed().aggregate(new PVAggregateFunction<>()).addSink(results::add);
        assertEquals(1, results.size());
        assertEquals(5L, results.get(0));
    }

    @Test
    void topKCountsRankedItemsInAWindow() {
        List<List<TopKAggregateFunction.TopKEntry<String>>> results = new ArrayList<>();
        windowed().aggregate(new TopKAggregateFunction<>(2)).addSink(results::add);
        assertEquals(1, results.size());
        List<TopKAggregateFunction.TopKEntry<String>> top = results.get(0);
        assertEquals(2, top.size());
        // counts tie at 2; ties break by item ascending
        assertEquals("a", top.get(0).item());
        assertEquals("b", top.get(1).item());
    }

    @Test
    void quantileRanksWindowValues() {
        List<Double> results = new ArrayList<>();
        KeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                (List.of(5, 1, 9, 3))::iterator, r -> "user");
        keyed.window(TumblingWindow.ofMillis(1000))
                .aggregate(new QuantileAggregateFunction<>(0.9))
                .addSink(results::add);
        assertEquals(1, results.size());
        // nearest rank floor(0.9 * (4 - 1)) = 2 over sorted {1, 3, 5, 9}
        assertTrue(Math.abs(5.0 - results.get(0)) < 1e-9);
    }
}
