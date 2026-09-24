package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers edge branches of {@link InMemoryKeyedStream}: exhausted iterators of
 * process/reduce/sum, non-numeric sum inputs, timer queue ordering/dedup/null-key and the
 * checkpoint-store registration branches of the private constructor.
 */
class InMemoryKeyedStreamEdgeCaseTest {

    @Test
    void emptyKeyedOperatorsThrowNoSuchOnNext() {
        InMemoryKeyedStream<String, Integer> empty =
                new InMemoryKeyedStream<>(() -> Collections.<Integer>emptyIterator(), v -> "k");

        assertEmptyIteratorThrows(empty.<String>process((key, value, ctx, out) -> out.collect("x")));
        assertEmptyIteratorThrows(empty.reduce((a, b) -> a));
        assertEmptyIteratorThrows(empty.sum(v -> v));
    }

    @Test
    void keyedSumRejectsNonNumberAndNullElements() {
        InMemoryKeyedStream<String, String> strings =
                new InMemoryKeyedStream<>(() -> List.of("s").iterator(), v -> "k");
        Exception e = assertThrows(Exception.class, () -> consume(strings.sum(v -> 1)));
        UnsupportedOperationException uoe = unwrapUoe(e);
        assertTrue(uoe.getMessage().contains("java.lang.String"));

        InMemoryKeyedStream<String, String> nulls =
                new InMemoryKeyedStream<>(() -> Collections.<String>singleton(null).iterator(), v -> "k");
        Exception e2 = assertThrows(Exception.class, () -> consume(nulls.sum(v -> 1)));
        assertTrue(unwrapUoe(e2).getMessage().contains("null"));
    }

    @Test
    void timerQueueOrdersDeduplicatesAndRejectsNullKeys() {
        List<String> fired = new ArrayList<>();
        KeyedProcessFunction<String, Integer, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, Integer value, Context ctx, Collector<String> out) {
                if (value == 0) {
                    // Two pending timers per queue with distinct and equal timestamps so both
                    // comparator lambdas run during insertion and during drain.
                    ctx.registerProcessingTimeTimer(20);
                    ctx.registerProcessingTimeTimer(10);
                    ctx.registerEventTimeTimer(20);
                    ctx.registerEventTimeTimer(10);
                    ctx.registerEventTimeTimer(10); // duplicate is dropped
                    ctx.registerProcessingTimeTimer(20); // duplicate is dropped
                } else {
                    // Same timestamp as an existing key's timer -> seq tie-break comparator path.
                    ctx.registerProcessingTimeTimer(10);
                    ctx.registerEventTimeTimer(10);
                }
                out.collect(key + ":" + value);
            }

            @Override
            public void onProcessingTime(long timestamp, String key, Context ctx, Collector<String> out) {
                fired.add("p:" + key + ":" + timestamp);
            }

            @Override
            public void onEventTime(long timestamp, String key, Context ctx, Collector<String> out) {
                fired.add("e:" + key + ":" + timestamp);
            }
        };

        InMemoryKeyedStream<String, Integer> keyed =
                new InMemoryKeyedStream<>(() -> List.of(0, 1).iterator(), v -> v == 0 ? "a" : "b");
        List<String> out = new ArrayList<>();
        keyed.<String>process(fn).addSink(out::add);

        assertEquals(List.of("a:0", "b:1"), out);
        assertTrue(fired.contains("p:a:10"));
        assertTrue(fired.contains("p:a:20"));
        assertTrue(fired.contains("p:b:10"));
        assertTrue(fired.contains("e:a:10"));
        assertTrue(fired.contains("e:a:20"));
        assertTrue(fired.contains("e:b:10"));
    }

    @Test
    void timerRegistrationWithoutCurrentKeyFails() {
        InMemoryKeyedStream<String, Integer> keyed =
                new InMemoryKeyedStream<>(() -> List.of(1).iterator(), v -> null);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> consume(keyed.<String>process((key, value, ctx, out) -> ctx.registerProcessingTimeTimer(5))));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("No current key"));
    }

    @Test
    void constructorCoversCheckpointStoreRegistrationAndReuseBranches() {
        InMemoryCheckpointCoordinator coordinator = new InMemoryCheckpointCoordinator();

        // keyBy with a coordinator registers a new store with the coordinator (mid ctor branch).
        InMemoryDataStream<Integer> source = InMemoryDataStream.fromRecords(
                () -> List.<InMemoryRecord<Integer>>of(new InMemoryRecord<>(1, 0L)).iterator(), coordinator);
        io.github.cuihairu.redis.streaming.api.stream.KeyedStream<Integer, Integer> keyed = source.keyBy(v -> v);

        // Chaining keeps the existing store id instead of registering again (other ctor branch).
        List<Integer> out = new ArrayList<>();
        keyed.map(v -> v + 1).reduce((a, b) -> a + b).addSink(out::add);
        assertEquals(List.of(2), out);
        assertEquals(1, coordinator.getRegisteredStores().size());

        // No coordinator at all (null branch).
        InMemoryKeyedStream<String, Integer> bare =
                new InMemoryKeyedStream<>(() -> List.of(1).iterator(), v -> "k");
        AtomicInteger count = new AtomicInteger();
        bare.map(v -> v).reduce((a, b) -> a + b).addSink(v -> count.incrementAndGet());
        assertEquals(1, count.get());
    }

    @Test
    void keyedValueStateClearRemovesCurrentKey() {
        InMemoryKeyedStateStore<String> store = new InMemoryKeyedStateStore<>();
        InMemoryKeyedValueState<String, Integer> state = new InMemoryKeyedValueState<String, Integer>(
                store, new io.github.cuihairu.redis.streaming.api.state.StateDescriptor<>("count", Integer.class, 0));
        store.setCurrentKey("k");
        state.update(10);
        state.clear();
        assertEquals(0, state.value());
    }

    private static void assertEmptyIteratorThrows(DataStream<?> stream) {
        Iterator<?> it = ((InMemoryDataStream<?>) stream).iterator();
        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    private static void consume(DataStream<?> stream) {
        ((InMemoryDataStream<?>) stream).iterator().forEachRemaining(v -> {
        });
    }

    private static UnsupportedOperationException unwrapUoe(Exception e) {
        Throwable current = e;
        while (current != null && !(current instanceof UnsupportedOperationException)) {
            current = current.getCause();
        }
        assertInstanceOf(UnsupportedOperationException.class, current);
        return (UnsupportedOperationException) current;
    }
}
