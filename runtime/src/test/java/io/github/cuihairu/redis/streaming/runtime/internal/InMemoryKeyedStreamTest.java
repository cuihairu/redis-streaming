package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.watermark.generators.AscendingTimestampWatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void processFiresProcessingTimeTimersBeforeNextElementAndAfterDrain() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1, 2, 3).iterator(),
                v -> "k"
        );

        KeyedProcessFunction<String, Integer, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, Integer value, Context ctx, Collector<String> out) {
                out.collect("e:" + value);
                ctx.registerProcessingTimeTimer(ctx.currentProcessingTime() + 1);
            }

            @Override
            public void onProcessingTime(long timestamp, String key, Context ctx, Collector<String> out) {
                out.collect("pt:" + timestamp);
            }
        };

        List<String> out = new ArrayList<>();
        keyed.process(fn).addSink(out::add);

        assertEquals(List.of("e:1", "pt:1", "e:2", "pt:2", "e:3", "pt:3"), out);
    }

    @Test
    void processFiresEventTimeTimers() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1, 2).iterator(),
                v -> "k"
        );

        KeyedProcessFunction<String, Integer, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, Integer value, Context ctx, Collector<String> out) {
                out.collect("e:" + value);
                ctx.registerEventTimeTimer(ctx.currentWatermark() + 1);
            }

            @Override
            public void onEventTime(long timestamp, String key, Context ctx, Collector<String> out) {
                out.collect("et:" + timestamp);
            }
        };

        List<String> out = new ArrayList<>();
        keyed.process(fn).addSink(out::add);

        assertEquals(List.of("e:1", "et:1", "e:2", "et:2"), out);
    }

    @Test
    void processDeduplicatesPendingTimers() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1).iterator(),
                v -> "k"
        );

        KeyedProcessFunction<String, Integer, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, Integer value, Context ctx, Collector<String> out) {
                ctx.registerProcessingTimeTimer(5);
                ctx.registerProcessingTimeTimer(5);
            }

            @Override
            public void onProcessingTime(long timestamp, String key, Context ctx, Collector<String> out) {
                out.collect("pt:" + timestamp);
            }
        };

        List<String> out = new ArrayList<>();
        keyed.process(fn).addSink(out::add);

        assertEquals(List.of("pt:5"), out);
    }

    @Test
    void eventTimeTimerFiresWhenWatermarkAdvances() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        List<String> out = new ArrayList<>();
        env.<String>addSource(ctx -> {
                    ctx.collectWithTimestamp("e1", 10);
                    ctx.collectWithTimestamp("e2", 20);
                })
                .assignTimestampsAndWatermarks(new AscendingTimestampWatermarkGenerator<>())
                .keyBy(v -> "k")
                .process(new KeyedProcessFunction<String, String, String>() {
                    @Override
                    public void processElement(String key, String value, Context ctx, Collector<String> out) {
                        out.collect("e:" + value);
                        if ("e1".equals(value)) {
                            ctx.registerEventTimeTimer(15);
                        }
                    }

                    @Override
                    public void onEventTime(long timestamp, String key, Context ctx, Collector<String> out) {
                        out.collect("et:" + timestamp);
                    }
                })
                .addSink(out::add);

        assertEquals(List.of("e:e1", "et:15", "e:e2"), out);
    }

    @Test
    void eventTimeTimerFiresWhenWatermarkAdvancesWithTimestampAssigner() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        record Event(long ts, String value) {
        }

        List<String> out = new ArrayList<>();
        env.fromElements(new Event(10, "e1"), new Event(20, "e2"))
                .assignTimestampsAndWatermarks((event, recordTs) -> event.ts(),
                        new AscendingTimestampWatermarkGenerator<>())
                .keyBy(v -> "k")
                .process(new KeyedProcessFunction<String, Event, String>() {
                    @Override
                    public void processElement(String key, Event value, Context ctx, Collector<String> out) {
                        out.collect("e:" + value.value());
                        if ("e1".equals(value.value())) {
                            ctx.registerEventTimeTimer(15);
                        }
                    }

                    @Override
                    public void onEventTime(long timestamp, String key, Context ctx, Collector<String> out) {
                        out.collect("et:" + timestamp);
                    }
                })
                .addSink(out::add);

        assertEquals(List.of("e:e1", "et:15", "e:e2"), out);
    }

    @Test
    void checkpointCanRestoreKeyedValueStateWithinRun() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment().enableCheckpointing();
        var coordinator = env.getCheckpointCoordinator();

        List<String> out = new ArrayList<>();
        AtomicLong checkpointId = new AtomicLong(-1);

        var keyed = env.fromElements(1, 2, 3).keyBy(v -> "k");
        var sumState = keyed.getState(new StateDescriptor<>("sum", Integer.class, 0));

        KeyedProcessFunction<String, Integer, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, Integer value, Context ctx, Collector<String> collector) {
                if (value == 2) {
                    sumState.update(999);
                    coordinator.restoreFromCheckpoint(checkpointId.get());
                    collector.collect("restored=" + sumState.value());
                }

                int next = sumState.value() + value;
                sumState.update(next);
                collector.collect("sum=" + next);

                if (value == 1) {
                    checkpointId.set(coordinator.triggerCheckpoint());
                }
            }
        };

        keyed.process(fn).addSink(out::add);

        assertEquals(List.of("sum=1", "restored=1", "sum=3", "sum=6"), out);
    }

    @Test
    void testSumWithIntegerValues() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(10, 20, 30, 15).iterator(),
                v -> v % 2 == 0 ? "even" : "odd"
        );

        List<Integer> out = new ArrayList<>();
        keyed.sum(Integer::intValue).addSink(out::add);

        // even: 10, 20, 30 => 10, 30, 60
        // odd: 15 => 15
        assertEquals(List.of(10, 30, 60, 15), out);
    }

    @Test
    void testSumWithLongValues() {
        InMemoryKeyedStream<String, Long> keyed = new InMemoryKeyedStream<>(
                () -> List.of(10L, 20L, 30L).iterator(),
                v -> "k"
        );

        List<Long> out = new ArrayList<>();
        keyed.sum(Long::longValue).addSink(out::add);

        assertEquals(List.of(10L, 30L, 60L), out);
    }

    @Test
    void testSumWithDoubleValues() {
        InMemoryKeyedStream<String, Double> keyed = new InMemoryKeyedStream<>(
                () -> List.of(10.5, 20.3, 30.2).iterator(),
                v -> "k"
        );

        List<Double> out = new ArrayList<>();
        keyed.sum(Double::doubleValue).addSink(out::add);

        assertEquals(List.of(10.5, 30.8, 61.0), out);
    }

    @Test
    void testSumWithFloatValues() {
        InMemoryKeyedStream<String, Float> keyed = new InMemoryKeyedStream<>(
                () -> List.of(10.5f, 20.3f).iterator(),
                v -> "k"
        );

        List<Float> out = new ArrayList<>();
        keyed.sum(Float::floatValue).addSink(out::add);

        assertEquals(List.of(10.5f, 30.8f), out);
    }

    @Test
    void testSumWithShortValues() {
        InMemoryKeyedStream<String, Short> keyed = new InMemoryKeyedStream<>(
                () -> List.of((short) 10, (short) 20).iterator(),
                v -> "k"
        );

        List<Short> out = new ArrayList<>();
        keyed.sum(Short::shortValue).addSink(out::add);

        assertEquals(List.of((short) 10, (short) 30), out);
    }

    @Test
    void testSumWithByteValues() {
        InMemoryKeyedStream<String, Byte> keyed = new InMemoryKeyedStream<>(
                () -> List.of((byte) 10, (byte) 20).iterator(),
                v -> "k"
        );

        List<Byte> out = new ArrayList<>();
        keyed.sum(Byte::byteValue).addSink(out::add);

        assertEquals(List.of((byte) 10, (byte) 30), out);
    }

    @Test
    void testSumWithNullFieldSelectorThrowsNPE() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1).iterator(),
                v -> "k"
        );

        assertThrows(NullPointerException.class, () -> keyed.sum(null));
    }

    @Test
    void testSumWithNonNumberElementThrowsException() {
        InMemoryKeyedStream<String, String> keyed = new InMemoryKeyedStream<>(
                () -> List.of("not-a-number").iterator(),
                v -> "k"
        );

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
                () -> keyed.sum(v -> null).addSink(v -> {}));

        assertTrue(ex.getMessage().contains("only supports Number elements"));
    }

    @Test
    void testSumWithMixedIntegerAndFloat() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(10, 20).iterator(),
                v -> "k"
        );

        List<Integer> out = new ArrayList<>();
        // Using float field selector should result in floating-point sum
        keyed.sum(v -> (float) v.intValue()).addSink(out::add);

        assertEquals(List.of(10, 30), out);
    }

    @Test
    void testSumWithMixedIntegerAndDouble() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(10, 20).iterator(),
                v -> "k"
        );

        List<Integer> out = new ArrayList<>();
        keyed.sum(v -> (double) v.intValue()).addSink(out::add);

        assertEquals(List.of(10, 30), out);
    }

    @Test
    void testMapPreservesKey() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1, 2).iterator(),
                v -> v % 2 == 0 ? "even" : "odd"
        );

        List<Integer> out = new ArrayList<>();
        keyed.map(v -> v * 10).reduce((a, b) -> a + b).addSink(out::add);

        // odd: 1 => 10
        // even: 2 => 20
        assertEquals(List.of(10, 20), out);
    }

    @Test
    void testMapWithNullMapperThrowsNPE() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1).iterator(),
                v -> "k"
        );

        assertThrows(NullPointerException.class, () -> keyed.map(null));
    }

    @Test
    void testWindowWithValidWindowAssigner() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1, 2, 3).iterator(),
                v -> "k"
        );

        assertNotNull(keyed.window(new io.github.cuihairu.redis.streaming.api.stream.WindowAssigner<>() {
            @Override
            public Iterable<Window> assignWindows(Integer element, long timestamp) {
                return List.of();
            }

            @Override
            public Trigger<Integer> getDefaultTrigger() {
                return null;
            }
        }));
    }

    @Test
    void testWindowWithNullAssignerThrowsNPE() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1).iterator(),
                v -> "k"
        );

        assertThrows(NullPointerException.class, () -> keyed.window(null));
    }

    @Test
    void testReduceWithMultipleKeys() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1, 2, 3, 4, 5, 6).iterator(),
                v -> v % 2 == 0 ? "even" : "odd"
        );

        List<Integer> out = new ArrayList<>();
        keyed.reduce((a, b) -> a + b).addSink(out::add);

        // odd: 1, 3, 5 => 1, 4, 9
        // even: 2, 4, 6 => 2, 6, 12
        assertEquals(List.of(1, 2, 4, 6, 9, 12), out);
    }

    @Test
    void testReduceWithFirstElement() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(5).iterator(),
                v -> "k"
        );

        List<Integer> out = new ArrayList<>();
        keyed.reduce((a, b) -> a + b).addSink(out::add);

        assertEquals(List.of(5), out);
    }

    @Test
    void testSumAccumulatesPerKey() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1, 2, 3, 4).iterator(),
                v -> v % 2 == 0 ? "even" : "odd"
        );

        List<Integer> out = new ArrayList<>();
        keyed.sum(Integer::intValue).addSink(out::add);

        // odd: 1, 3 => 1, 4
        // even: 2, 4 => 2, 6
        assertEquals(List.of(1, 2, 4, 6), out);
    }

    @Test
    void testSumWithEmptyInput() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.<Integer>of().iterator(),
                v -> "k"
        );

        List<Integer> out = new ArrayList<>();
        keyed.sum(Integer::intValue).addSink(out::add);

        assertTrue(out.isEmpty());
    }

    @Test
    void testReduceWithEmptyInput() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.<Integer>of().iterator(),
                v -> "k"
        );

        List<Integer> out = new ArrayList<>();
        keyed.reduce((a, b) -> a + b).addSink(out::add);

        assertTrue(out.isEmpty());
    }

    @Test
    void testProcessWithEmptyInput() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.<Integer>of().iterator(),
                v -> "k"
        );

        KeyedProcessFunction<String, Integer, String> fn = (key, value, ctx, out) -> {
            out.collect("value:" + value);
        };

        List<String> out = new ArrayList<>();
        keyed.process(fn).addSink(out::add);

        assertTrue(out.isEmpty());
    }

    @Test
    void testSumWithNullNumberField() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(10, 20).iterator(),
                v -> "k"
        );

        List<Integer> out = new ArrayList<>();
        // Field selector returning null is treated as 0
        keyed.sum(v -> null).addSink(out::add);

        // The value itself is a Number (10, 20), but fieldSelector returns null
        // So the sum is: 0 + null = 0 for each element
        assertEquals(List.of(0, 0), out);
    }

    @Test
    void testGetStateReturnsValueState() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1).iterator(),
                v -> "k"
        );

        assertNotNull(keyed.getState(new StateDescriptor<>("test", Integer.class, 0)));
    }

    @Test
    void testProcessWithTimerException() {
        InMemoryKeyedStream<String, Integer> keyed = new InMemoryKeyedStream<>(
                () -> List.of(1).iterator(),
                v -> "k"
        );

        KeyedProcessFunction<String, Integer, String> fn = new KeyedProcessFunction<>() {
            @Override
            public void processElement(String key, Integer value, Context ctx, Collector<String> out) {
                ctx.registerProcessingTimeTimer(10);
            }

            @Override
            public void onProcessingTime(long timestamp, String key, Context ctx, Collector<String> out) {
                throw new RuntimeException("Timer boom");
            }
        };

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> keyed.process(fn).addSink(v -> {}));

        assertEquals("Keyed process timer callback failed", ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals("Timer boom", ex.getCause().getMessage());
    }
}
