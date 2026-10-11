package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.api.stream.WindowedStream;
import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator.WatermarkOutput;
import io.github.cuihairu.redis.streaming.runtime.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Event-time windows must close while the input is still running (driven by the upstream watermark
 * state carried through {@code keyBy().window()}), and the incremental bucket accumulators
 * (reduce/sum/count/aggregate) must produce exactly the results the previous collect-then-fold
 * engine produced.
 */
class InMemoryWindowedStreamWatermarkTest {

    /** Tumbling windows whose default trigger fires (and purges) on window-end event time. */
    static final class FiringTumblingWindowAssigner<T> implements WindowAssigner<T> {
        private final long size;
        private final PerEventWatermarkGenerator<T> generator;
        private final List<Long> eventTimeFires;
        private final AtomicLong eventsSeenAtFirstFire;

        FiringTumblingWindowAssigner(long size, PerEventWatermarkGenerator<T> generator) {
            this.size = size;
            this.generator = generator;
            this.eventTimeFires = generator == null ? null : new ArrayList<>();
            this.eventsSeenAtFirstFire = generator == null ? null : new AtomicLong(-1L);
        }

        @Override
        public Iterable<Window> assignWindows(T element, long timestamp) {
            long start = (timestamp / size) * size;
            return List.of(new SimpleWindow(start, start + size));
        }

        @Override
        public Trigger<T> getDefaultTrigger() {
            return new Trigger<>() {
                @Override
                public TriggerResult onElement(T element, long timestamp, Window window) {
                    return TriggerResult.CONTINUE;
                }

                @Override
                public TriggerResult onProcessingTime(long time, Window window) {
                    return TriggerResult.CONTINUE;
                }

                @Override
                public TriggerResult onEventTime(long time, Window window) {
                    if (eventTimeFires != null) {
                        eventTimeFires.add(time);
                        eventsSeenAtFirstFire.compareAndSet(-1L, generator.eventsSeen.get());
                    }
                    return TriggerResult.FIRE_AND_PURGE;
                }
            };
        }
    }

    static final class SimpleWindow implements WindowAssigner.Window {
        private final long start;
        private final long end;

        SimpleWindow(long start, long end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public long getStart() {
            return start;
        }

        @Override
        public long getEnd() {
            return end;
        }
    }

    /** Advances the watermark to each event's own timestamp and counts the events seen so far. */
    static final class PerEventWatermarkGenerator<T> implements WatermarkGenerator<T> {
        final AtomicLong eventsSeen = new AtomicLong();

        @Override
        public void onEvent(T event, long eventTimestamp, WatermarkOutput output) {
            eventsSeen.incrementAndGet();
            output.emitWatermark(new Watermark(eventTimestamp));
        }

        @Override
        public void onPeriodicEmit(WatermarkOutput output) {
            // no periodic watermarks
        }
    }

    /** Runs the pipeline but never advances the watermark: everything closes at end-of-input. */
    static final class NoWatermarkGenerator<T> implements WatermarkGenerator<T> {
        @Override
        public void onEvent(T event, long eventTimestamp, WatermarkOutput output) {
        }

        @Override
        public void onPeriodicEmit(WatermarkOutput output) {
        }
    }

    static final class SumAggregate implements AggregateFunction<Integer, Long> {
        @Override
        public Accumulator<Integer> createAccumulator() {
            return new IntAcc();
        }

        @Override
        public Accumulator<Integer> add(Integer value, Accumulator<Integer> accumulator) {
            ((IntAcc) accumulator).value += value;
            return accumulator;
        }

        @Override
        public Long getResult(Accumulator<Integer> accumulator) {
            return (long) ((IntAcc) accumulator).value;
        }

        @Override
        public Accumulator<Integer> merge(Accumulator<Integer> a, Accumulator<Integer> b) {
            ((IntAcc) a).value += ((IntAcc) b).value;
            return a;
        }
    }

    static final class IntAcc implements AggregateFunction.Accumulator<Integer> {
        private static final long serialVersionUID = 1L;
        int value;
    }

    private static Integer[] hundredElements() {
        return IntStream.range(0, 100).boxed().toArray(Integer[]::new);
    }

    private static <R> List<R> runPipeline(WatermarkGenerator<Integer> generator,
                                           Function<WindowedStream<String, Integer>, DataStream<R>> windowOp) {
        List<R> out = new ArrayList<>();
        WindowedStream<String, Integer> windowed = StreamExecutionEnvironment.getExecutionEnvironment()
                .fromElements(hundredElements())
                .assignTimestampsAndWatermarks(generator)
                .keyBy(v -> "k")
                .window(new FiringTumblingWindowAssigner<>(10, null));
        windowOp.apply(windowed).addSink(out::add);
        return out;
    }

    @Test
    void watermarkClosesWindowsBeforeInputEnds() {
        PerEventWatermarkGenerator<Integer> gen = new PerEventWatermarkGenerator<>();
        FiringTumblingWindowAssigner<Integer> assigner = new FiringTumblingWindowAssigner<>(10, gen);
        List<Long> out = new ArrayList<>();
        StreamExecutionEnvironment.getExecutionEnvironment()
                .fromElements(hundredElements())
                .assignTimestampsAndWatermarks(gen)
                .keyBy(v -> "k")
                .window(assigner)
                .count()
                .addSink(out::add);

        // Element i has value == timestamp == i, and the generator emits watermark i on it. So
        // window [0,10) closes while event 10 is being processed: onEventTime fires at 10, 20, ...,
        // 90 mid-stream, and the last window [90,100) at the end-of-input flush (100).
        assertEquals(List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L), assigner.eventTimeFires);
        // The first close happened after 11 events (0..10) — long before the 100th.
        assertEquals(11L, assigner.eventsSeenAtFirstFire.get());

        assertEquals(10, out.size());
        for (Long count : out) {
            assertEquals(10L, count);
        }
    }

    @Test
    void countMatchesUnwatermarkedRun() {
        // Every window holds 10 elements, so emission order cannot matter.
        assertEquals(List.of(10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L),
                runPipeline(new NoWatermarkGenerator<>(), WindowedStream::count));
        assertEquals(List.of(10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L),
                runPipeline(new PerEventWatermarkGenerator<>(), WindowedStream::count));
    }

    @Test
    void sumMatchesUnwatermarkedRun() {
        // Window sums: 0+..+9 = 45, 10+..+19 = 145, ... — identical either way. The unwatermarked
        // run flushes windows in hash order while the watermarked run closes them in event-time
        // order, so compare sorted, not emission order.
        List<Integer> unwatermarked = runPipeline(new NoWatermarkGenerator<>(), w -> w.sum(v -> v));
        List<Integer> watermarked = runPipeline(new PerEventWatermarkGenerator<>(), w -> w.sum(v -> v));
        Collections.sort(unwatermarked);
        Collections.sort(watermarked);
        assertEquals(unwatermarked, watermarked);
        assertEquals(List.of(45, 145, 245, 345, 445, 545, 645, 745, 845, 945), watermarked);
    }

    @Test
    void reduceMatchesUnwatermarkedRun() {
        List<Integer> unwatermarked = runPipeline(new NoWatermarkGenerator<>(), w -> w.reduce((a, b) -> a + b));
        List<Integer> watermarked = runPipeline(new PerEventWatermarkGenerator<>(), w -> w.reduce((a, b) -> a + b));
        Collections.sort(unwatermarked);
        Collections.sort(watermarked);
        assertEquals(unwatermarked, watermarked);
    }

    @Test
    void aggregateMatchesUnwatermarkedRun() {
        List<Long> unwatermarked = runPipeline(new NoWatermarkGenerator<>(), w -> w.aggregate(new SumAggregate()));
        List<Long> watermarked = runPipeline(new PerEventWatermarkGenerator<>(), w -> w.aggregate(new SumAggregate()));
        Collections.sort(unwatermarked);
        Collections.sort(watermarked);
        assertEquals(unwatermarked, watermarked);
    }

    @Test
    void applySeesFullWindowContentsWithWatermarks() {
        // apply() keeps the raw elements path; each window must still deliver its 10 elements,
        // in order, when windows close mid-stream.
        assertEquals(
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19),
                runPipeline(new PerEventWatermarkGenerator<>(),
                        w -> w.apply((key, window, elements, out) -> {
                            for (Integer e : elements) {
                                out.collect(e);
                            }
                        })).subList(0, 20));
    }

    @Test
    void fireWithoutPurgeKeepsBucketUntilEndFlush() {
        // Direct construction: the watermark state is advanced by hand, mirroring what
        // assignTimestampsAndWatermarks does upstream.
        WatermarkState watermarkState = new WatermarkState();
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 0, 0L),
                new KeyedRecord<>("k", 9, 9L),
                new KeyedRecord<>("k", 10, 100L)
        );
        Iterator<KeyedRecord<String, Integer>> source = records.iterator();
        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(() -> new Iterator<>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public KeyedRecord<String, Integer> next() {
                KeyedRecord<String, Integer> record = source.next();
                watermarkState.emit(new Watermark(record.timestamp()));
                return record;
            }
        }, new FireOnEventTimeAssigner<>(10, false), watermarkState);

        List<Integer> out = new ArrayList<>();
        windowed.reduce((a, b) -> a + b).addSink(out::add);

        // The watermark reaches 100 while processing the last record: window [0,10) fires (0+9=9)
        // mid-stream and, because the trigger returns FIRE instead of FIRE_AND_PURGE, the bucket
        // stays open and fires once more at the end-of-input flush. The trailing 10 is the
        // [100,110) window's own end-of-input result.
        assertEquals(List.of(9, 9, 10), out);
    }

    @Test
    void fireAndPurgeRemovesBucketAtWatermarkClose() {
        WatermarkState watermarkState = new WatermarkState();
        List<KeyedRecord<String, Integer>> records = List.of(
                new KeyedRecord<>("k", 0, 0L),
                new KeyedRecord<>("k", 9, 9L),
                new KeyedRecord<>("k", 10, 100L)
        );
        Iterator<KeyedRecord<String, Integer>> source = records.iterator();
        InMemoryWindowedStream<String, Integer> windowed = new InMemoryWindowedStream<>(() -> new Iterator<>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public KeyedRecord<String, Integer> next() {
                KeyedRecord<String, Integer> record = source.next();
                watermarkState.emit(new Watermark(record.timestamp()));
                return record;
            }
        }, new FireOnEventTimeAssigner<>(10, true), watermarkState);

        List<Integer> out = new ArrayList<>();
        windowed.reduce((a, b) -> a + b).addSink(out::add);

        // With FIRE_AND_PURGE the [0,10) bucket is removed at the watermark close, so the
        // end-of-input flush cannot re-emit it (the FIRE variant above emits 9 twice). The
        // trailing 10 is the [100,110) window's own end-of-input result.
        assertEquals(List.of(9, 10), out);
    }

    /** Tumbling windows with a configurable FIRE / FIRE_AND_PURGE on window-end event time. */
    static final class FireOnEventTimeAssigner<T> implements WindowAssigner<T> {
        private final long size;
        private final boolean purge;

        FireOnEventTimeAssigner(long size, boolean purge) {
            this.size = size;
            this.purge = purge;
        }

        @Override
        public Iterable<Window> assignWindows(T element, long timestamp) {
            long start = (timestamp / size) * size;
            return List.of(new SimpleWindow(start, start + size));
        }

        @Override
        public Trigger<T> getDefaultTrigger() {
            return new Trigger<>() {
                @Override
                public TriggerResult onElement(T element, long timestamp, Window window) {
                    return TriggerResult.CONTINUE;
                }

                @Override
                public TriggerResult onProcessingTime(long time, Window window) {
                    return TriggerResult.CONTINUE;
                }

                @Override
                public TriggerResult onEventTime(long time, Window window) {
                    return purge ? TriggerResult.FIRE_AND_PURGE : TriggerResult.FIRE;
                }
            };
        }
    }
}
