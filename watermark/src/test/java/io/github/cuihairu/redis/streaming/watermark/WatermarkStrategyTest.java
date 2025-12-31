package io.github.cuihairu.redis.streaming.watermark;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import io.github.cuihairu.redis.streaming.watermark.generators.AscendingTimestampWatermarkGenerator;
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WatermarkStrategyTest {

    @Test
    void testMonotonousTimestampsStrategy() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forMonotonousTimestamps();

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();

        assertNotNull(generator);
        assertTrue(generator instanceof AscendingTimestampWatermarkGenerator);
    }

    @Test
    void testBoundedOutOfOrdernessStrategy() {
        Duration maxDelay = Duration.ofSeconds(5);
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forBoundedOutOfOrderness(maxDelay);

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();

        assertNotNull(generator);
        assertTrue(generator instanceof BoundedOutOfOrdernessWatermarkGenerator);

        BoundedOutOfOrdernessWatermarkGenerator<Event> boundedGenerator =
                (BoundedOutOfOrdernessWatermarkGenerator<Event>) generator;
        assertEquals(5000, boundedGenerator.getMaxOutOfOrdernessMillis());
    }

    @Test
    void testWithTimestampAssigner() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forMonotonousTimestamps()
                .withTimestampAssigner((event, recordTimestamp) -> event.getTimestamp());

        assertNotNull(strategy.getTimestampAssigner());

        Event event = new Event(1000, "test");
        long extractedTimestamp = strategy.extractTimestamp(event, 0);

        assertEquals(1000, extractedTimestamp);
    }

    @Test
    void testWithTimestampAssignerLambda() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(2))
                .withTimestampAssigner((event, recordTimestamp) -> event.timestamp);

        Event event = new Event(5000, "test");
        assertEquals(5000, strategy.extractTimestamp(event, 0));
    }

    @Test
    void testNoWatermarksStrategy() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy.noWatermarks();

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
        assertNotNull(generator);

        TestWatermarkOutput output = new TestWatermarkOutput();
        generator.onEvent(new Event(1, "x"), 1, output);
        generator.onPeriodicEmit(output);
        assertTrue(output.getWatermarks().isEmpty());
    }

    @Test
    void testCustomGenerator() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forGenerator(
                () -> new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(10))
        );

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
        assertNotNull(generator);
        assertTrue(generator instanceof BoundedOutOfOrdernessWatermarkGenerator);
    }

    @Test
    void testExtractTimestampFallsBackToRecordTimestamp() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forMonotonousTimestamps();
        Event event = new Event(5000, "test");
        assertEquals(123L, strategy.extractTimestamp(event, 123L));
    }

    @Test
    void testBoundedOutOfOrdernessWithZeroDelay() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forBoundedOutOfOrderness(Duration.ZERO);

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
        assertNotNull(generator);
        assertTrue(generator instanceof BoundedOutOfOrdernessWatermarkGenerator);
    }

    @Test
    void testBoundedOutOfOrdernessWithLargeDelay() {
        Duration largeDelay = Duration.ofHours(1);
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forBoundedOutOfOrderness(largeDelay);

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
        assertNotNull(generator);

        BoundedOutOfOrdernessWatermarkGenerator<Event> boundedGenerator =
                (BoundedOutOfOrdernessWatermarkGenerator<Event>) generator;
        assertEquals(3600000, boundedGenerator.getMaxOutOfOrdernessMillis());
    }

    @Test
    void testWithTimestampAssignerReturnsNegativeTimestamp() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forMonotonousTimestamps()
                .withTimestampAssigner((event, recordTimestamp) -> -1L);

        Event event = new Event(5000, "test");
        assertEquals(-1L, strategy.extractTimestamp(event, 123L));
    }

    @Test
    void testWithTimestampAssignerReturnsZero() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forMonotonousTimestamps()
                .withTimestampAssigner((event, recordTimestamp) -> 0L);

        Event event = new Event(5000, "test");
        assertEquals(0L, strategy.extractTimestamp(event, 123L));
    }

    @Test
    void testWithTimestampAssignerUsingRecordTimestamp() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forMonotonousTimestamps()
                .withTimestampAssigner((event, recordTimestamp) -> recordTimestamp);

        Event event = new Event(5000, "test");
        assertEquals(999L, strategy.extractTimestamp(event, 999L));
    }

    @Test
    void testExtractTimestampWithoutAssignerUsesRecordTimestamp() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forMonotonousTimestamps();

        // Without custom assigner, should fall back to record timestamp
        Event event = new Event(5000, "test");
        assertEquals(777L, strategy.extractTimestamp(event, 777L));
    }

    @Test
    void testMultipleWithTimestampAssignerCallsOverride() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forMonotonousTimestamps()
                .withTimestampAssigner((event, recordTimestamp) -> 100L)
                .withTimestampAssigner((event, recordTimestamp) -> 200L);

        // Last assigner wins
        Event event = new Event(5000, "test");
        assertEquals(200L, strategy.extractTimestamp(event, 999L));
    }

    @Test
    void testForMonotonousTimestampsCreatesAscendingGenerator() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forMonotonousTimestamps();

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
        assertTrue(generator instanceof AscendingTimestampWatermarkGenerator);
    }

    @Test
    void testNoWatermarksIgnoresEvents() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy.noWatermarks();

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent(new Event(1000, "test1"), 1000, output);
        generator.onEvent(new Event(2000, "test2"), 2000, output);
        generator.onEvent(new Event(3000, "test3"), 3000, output);
        generator.onPeriodicEmit(output);

        assertTrue(output.getWatermarks().isEmpty());
    }

    @Test
    void testCustomGeneratorWithDifferentTypes() {
        WatermarkStrategy<String> strategy = WatermarkStrategy.forGenerator(
                () -> new AscendingTimestampWatermarkGenerator<>()
        );

        WatermarkGenerator<String> generator = strategy.createWatermarkGenerator();
        assertNotNull(generator);
        assertTrue(generator instanceof AscendingTimestampWatermarkGenerator);
    }

    @Test
    void testTimestampAssignerWithNullEvent() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forMonotonousTimestamps()
                .withTimestampAssigner((event, recordTimestamp) -> event == null ? -1L : event.timestamp);

        assertEquals(-1L, strategy.extractTimestamp(null, 123L));
    }

    @Test
    void testExtractTimestampWithMaxLongValue() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forMonotonousTimestamps()
                .withTimestampAssigner((event, recordTimestamp) -> Long.MAX_VALUE);

        Event event = new Event(5000, "test");
        assertEquals(Long.MAX_VALUE, strategy.extractTimestamp(event, 0));
    }

    @Test
    void testExtractTimestampWithMinLongValue() {
        WatermarkStrategy<Event> strategy = WatermarkStrategy
                .<Event>forMonotonousTimestamps()
                .withTimestampAssigner((event, recordTimestamp) -> Long.MIN_VALUE);

        Event event = new Event(5000, "test");
        assertEquals(Long.MIN_VALUE, strategy.extractTimestamp(event, 0));
    }

    @Test
    void testBoundedOutOfOrdernessWithNanos() {
        Duration nanoDelay = Duration.ofNanos(1_000_000); // 1 millisecond
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forBoundedOutOfOrderness(nanoDelay);

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
        assertNotNull(generator);

        BoundedOutOfOrdernessWatermarkGenerator<Event> boundedGenerator =
                (BoundedOutOfOrdernessWatermarkGenerator<Event>) generator;
        assertEquals(1, boundedGenerator.getMaxOutOfOrdernessMillis());
    }

    @Test
    void testBoundedOutOfOrdernessWithMillis() {
        Duration millisDelay = Duration.ofMillis(500);
        WatermarkStrategy<Event> strategy = WatermarkStrategy.forBoundedOutOfOrderness(millisDelay);

        WatermarkGenerator<Event> generator = strategy.createWatermarkGenerator();
        assertNotNull(generator);

        BoundedOutOfOrdernessWatermarkGenerator<Event> boundedGenerator =
                (BoundedOutOfOrdernessWatermarkGenerator<Event>) generator;
        assertEquals(500, boundedGenerator.getMaxOutOfOrdernessMillis());
    }

    /**
     * Test event class
     */
    private static class Event {
        private final long timestamp;
        private final String data;

        public Event(long timestamp, String data) {
            this.timestamp = timestamp;
            this.data = data;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getData() {
            return data;
        }
    }

    private static class TestWatermarkOutput implements WatermarkGenerator.WatermarkOutput {
        private final List<Watermark> watermarks = new ArrayList<>();

        @Override
        public void emitWatermark(Watermark watermark) {
            watermarks.add(watermark);
        }

        @Override
        public void markIdle() {
        }

        @Override
        public void markActive() {
        }

        public List<Watermark> getWatermarks() {
            return watermarks;
        }
    }
}
