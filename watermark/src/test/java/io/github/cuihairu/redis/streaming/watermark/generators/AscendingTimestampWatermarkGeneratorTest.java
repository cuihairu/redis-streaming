package io.github.cuihairu.redis.streaming.watermark.generators;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

