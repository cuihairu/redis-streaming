package io.github.cuihairu.redis.streaming.watermark.generators;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoundedOutOfOrdernessWatermarkGeneratorTest {

    @Test
    void testWatermarkGeneration() {
        // Create generator with 5 seconds max out of orderness
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(5));

        TestWatermarkOutput output = new TestWatermarkOutput();

        // Process events with timestamps
        generator.onEvent("event1", 1000, output);
        generator.onEvent("event2", 2000, output);
        generator.onEvent("event3", 3000, output);

        // Emit watermark
        generator.onPeriodicEmit(output);

        // Watermark should be: max_timestamp (3000) - max_out_of_orderness (5000) - 1 = -2001
        assertFalse(output.getWatermarks().isEmpty());
        assertEquals(-2001, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testOutOfOrderEvents() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(2));

        TestWatermarkOutput output = new TestWatermarkOutput();

        // Process out-of-order events
        generator.onEvent("event1", 5000, output);
        generator.onEvent("event2", 3000, output);  // Out of order
        generator.onEvent("event3", 7000, output);
        generator.onEvent("event4", 4000, output);  // Out of order

        generator.onPeriodicEmit(output);

        // Max timestamp is 7000, watermark = 7000 - 2000 - 1 = 4999
        assertEquals(4999, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testMaxTimestampTracking() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofSeconds(1));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event1", 1000, output);
        assertEquals(1000, generator.getMaxTimestamp());

        generator.onEvent("event2", 500, output);  // Earlier timestamp
        assertEquals(1000, generator.getMaxTimestamp());  // Should not change

        generator.onEvent("event3", 2000, output);
        assertEquals(2000, generator.getMaxTimestamp());
    }

    @Test
    void testGetters() {
        Duration maxOutOfOrderness = Duration.ofSeconds(3);
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(maxOutOfOrderness);

        assertEquals(3000, generator.getMaxOutOfOrdernessMillis());
    }

    /**
     * Test implementation of WatermarkOutput
     */
    private static class TestWatermarkOutput implements WatermarkGenerator.WatermarkOutput {
        private final List<Watermark> watermarks = new ArrayList<>();
        private boolean idle = false;
        private boolean active = false;

        @Override
        public void emitWatermark(Watermark watermark) {
            watermarks.add(watermark);
        }

        @Override
        public void markIdle() {
            idle = true;
        }

        @Override
        public void markActive() {
            active = true;
        }

        public List<Watermark> getWatermarks() {
            return watermarks;
        }

        public boolean isIdle() {
            return idle;
        }

        public boolean isActive() {
            return active;
        }
    }
}
