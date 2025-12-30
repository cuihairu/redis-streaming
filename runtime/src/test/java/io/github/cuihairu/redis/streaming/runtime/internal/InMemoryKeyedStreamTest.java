package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.api.stream.ReduceFunction;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import io.github.cuihairu.redis.streaming.watermark.generators.AscendingTimestampWatermarkGenerator;
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
}
