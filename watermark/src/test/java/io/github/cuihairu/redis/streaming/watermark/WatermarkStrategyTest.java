package io.github.cuihairu.redis.streaming.watermark;

import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import io.github.cuihairu.redis.streaming.watermark.generators.AscendingTimestampWatermarkGenerator;
import io.github.cuihairu.redis.streaming.watermark.generators.BoundedOutOfOrdernessWatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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

        // Should not emit watermarks (no easy way to test without mocking)
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
}
