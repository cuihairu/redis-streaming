package io.github.cuihairu.redis.streaming.watermark.generators;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AscendingTimestampWatermarkGeneratorTest {

    @Test
    void emitsLatestTimestampMinusOne() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 1000, output);
        assertEquals(999, generator.getCurrentWatermark());

        generator.onEvent("e2", 2000, output);
        assertEquals(1999, generator.getCurrentWatermark());

        generator.onPeriodicEmit(output);
        assertFalse(output.getWatermarks().isEmpty());
        assertEquals(1999, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void ignoresOutOfOrderTimestamps() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 1000, output);
        generator.onEvent("late", 900, output);

        assertEquals(999, generator.getCurrentWatermark());
    }

    @Test
    void initialWatermarkIsLongMinValue() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();

        assertEquals(Long.MIN_VALUE, generator.getCurrentWatermark());
    }

    @Test
    void onEventUpdatesWatermark() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event", 5000, output);

        assertEquals(4999, generator.getCurrentWatermark());
    }

    @Test
    void onEventWithZeroTimestamp() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event", 0, output);

        assertEquals(-1, generator.getCurrentWatermark());
    }

    @Test
    void onEventWithNegativeTimestamp() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event", -1000, output);

        assertEquals(-1001, generator.getCurrentWatermark());
    }

    @Test
    void onEventWithMaxLongTimestamp() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event", Long.MAX_VALUE, output);

        assertEquals(Long.MAX_VALUE - 1, generator.getCurrentWatermark());
    }

    @Test
    void onEventWithSameTimestampDoesNotChangeWatermark() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 1000, output);
        long firstWatermark = generator.getCurrentWatermark();

        generator.onEvent("e2", 1000, output);

        assertEquals(firstWatermark, generator.getCurrentWatermark());
        assertEquals(999, generator.getCurrentWatermark());
    }

    @Test
    void onEventWithLowerTimestampDoesNotChangeWatermark() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 5000, output);
        generator.onEvent("e2", 3000, output);

        assertEquals(4999, generator.getCurrentWatermark());
    }

    @Test
    void onPeriodicEmitEmitsWatermark() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 1000, output);
        output.getWatermarks().clear();
        generator.onPeriodicEmit(output);

        assertEquals(1, output.getWatermarks().size());
        assertEquals(999, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void onPeriodicEmitMultipleTimes() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 1000, output);

        generator.onPeriodicEmit(output);
        generator.onPeriodicEmit(output);
        generator.onPeriodicEmit(output);

        assertEquals(3, output.getWatermarks().size());
        // All should have the same timestamp since no new events
        for (Watermark w : output.getWatermarks()) {
            assertEquals(999, w.getTimestamp());
        }
    }

    @Test
    void multipleEventsAscendingTimestamps() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 1000, output);
        assertEquals(999, generator.getCurrentWatermark());

        generator.onEvent("e2", 2000, output);
        assertEquals(1999, generator.getCurrentWatermark());

        generator.onEvent("e3", 3000, output);
        assertEquals(2999, generator.getCurrentWatermark());

        generator.onEvent("e4", 4000, output);
        assertEquals(3999, generator.getCurrentWatermark());
    }

    @Test
    void watermarkUpdatesOnlyOnHigherTimestamp() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 5000, output);
        generator.onEvent("e2", 3000, output);
        generator.onEvent("e3", 4000, output);
        generator.onEvent("e4", 2000, output);

        // Should stay at the highest timestamp
        assertEquals(4999, generator.getCurrentWatermark());
    }

    @Test
    void largeTimestampJumps() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 1000, output);
        generator.onEvent("e2", 1000000, output);

        assertEquals(999999, generator.getCurrentWatermark());
    }

    @Test
    void consecutiveTimestamps() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        for (long i = 0; i < 10; i++) {
            generator.onEvent("e" + i, i * 100, output);
        }

        assertEquals(899, generator.getCurrentWatermark());
    }

    @Test
    void emitsCurrentWatermarkOnPeriodicEmit() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("e1", 12345, output);
        generator.onPeriodicEmit(output);

        assertEquals(12344, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void handlesNullEvent() {
        AscendingTimestampWatermarkGenerator<String> generator = new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent(null, 1000, output);

        assertEquals(999, generator.getCurrentWatermark());
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

