package io.github.cuihairu.redis.streaming.watermark;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Covers the {@code NoWatermarkGenerator} no-op callbacks used by {@code WatermarkStrategy#noWatermarks}. */
class NoWatermarkGeneratorCoverageTest {

    private static final WatermarkGenerator.WatermarkOutput OUTPUT = new WatermarkGenerator.WatermarkOutput() {
        @Override
        public void emitWatermark(Watermark watermark) { }
        @Override
        public void markIdle() { }
        @Override
        public void markActive() { }
    };

    @Test
    void noWatermarksGeneratorCallbacksAreNoOps() {
        WatermarkStrategy<Object> strategy = WatermarkStrategy.noWatermarks();
        assertNotNull(strategy);
        WatermarkGenerator<Object> generator = strategy.createWatermarkGenerator();
        assertNotNull(generator);

        assertDoesNotThrow(() -> generator.onEvent(new Object(), 1L, OUTPUT));
        assertDoesNotThrow(() -> generator.onPeriodicEmit(OUTPUT));
    }
}
