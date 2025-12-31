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

    @Test
    void testSingleEvent() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();
        generator.onEvent("event", 1000, output);
        generator.onPeriodicEmit(output);

        // Watermark should be: 1000 - 100 - 1 = 899
        assertEquals(899, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testWithZeroOutOfOrderness() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(0));

        TestWatermarkOutput output = new TestWatermarkOutput();
        generator.onEvent("event1", 1000, output);
        generator.onPeriodicEmit(output);

        // Watermark should be: 1000 - 0 - 1 = 999
        assertEquals(999, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testWithLargeOutOfOrderness() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMinutes(10));

        TestWatermarkOutput output = new TestWatermarkOutput();
        generator.onEvent("event", 5000, output);
        generator.onPeriodicEmit(output);

        // Watermark should be: 5000 - 600000 - 1 = -595001
        assertTrue(output.getWatermarks().get(0).getTimestamp() < 0);
    }

    @Test
    void testMultiplePeriodicEmits() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();
        generator.onEvent("event1", 10000, output);

        generator.onPeriodicEmit(output);
        generator.onPeriodicEmit(output);
        generator.onPeriodicEmit(output);

        // All should have the same watermark since no new events
        assertEquals(3, output.getWatermarks().size());
        for (Watermark w : output.getWatermarks()) {
            assertEquals(9899, w.getTimestamp());
        }
    }

    @Test
    void testWatermarkUpdatesWithNewMaxTimestamp() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event1", 1000, output);
        generator.onPeriodicEmit(output);
        assertEquals(899, output.getWatermarks().get(0).getTimestamp());

        output.getWatermarks().clear();

        generator.onEvent("event2", 2000, output);
        generator.onPeriodicEmit(output);
        assertEquals(1899, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testEventsWithSameTimestamp() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event1", 1000, output);
        generator.onEvent("event2", 1000, output);
        generator.onEvent("event3", 1000, output);

        generator.onPeriodicEmit(output);

        // Max timestamp is still 1000
        assertEquals(899, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testDescendingTimestamps() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event1", 5000, output);
        generator.onEvent("event2", 4000, output);
        generator.onEvent("event3", 3000, output);
        generator.onEvent("event4", 2000, output);

        generator.onPeriodicEmit(output);

        // Max timestamp should still be 5000 (first event)
        assertEquals(4899, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testAscendingTimestamps() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event1", 1000, output);
        generator.onEvent("event2", 2000, output);
        generator.onEvent("event3", 3000, output);
        generator.onEvent("event4", 4000, output);

        generator.onPeriodicEmit(output);

        // Max timestamp should be 4000
        assertEquals(3899, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testWithNegativeTimestamps() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event", -1000, output);
        generator.onPeriodicEmit(output);

        // Watermark should be: -1000 - 100 - 1 = -1101
        assertEquals(-1101, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testWithZeroTimestamp() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event", 0, output);
        generator.onPeriodicEmit(output);

        // Watermark should be: 0 - 100 - 1 = -101
        assertEquals(-101, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testWithMaxLongTimestamp() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event", Long.MAX_VALUE, output);
        generator.onPeriodicEmit(output);

        // Watermark should be: MAX_VALUE - 100 - 1
        assertEquals(Long.MAX_VALUE - 101, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testMixedOrderedAndUnorderedEvents() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(500));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event1", 1000, output);  // Ordered
        generator.onEvent("event2", 3000, output);  // Ordered
        generator.onEvent("event3", 2000, output);  // Out of order
        generator.onEvent("event4", 5000, output);  // Ordered
        generator.onEvent("event5", 1500, output);  // Out of order

        generator.onPeriodicEmit(output);

        // Max timestamp is 5000
        assertEquals(4499, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testWatermarkNeverDecreases() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event1", 5000, output);
        generator.onPeriodicEmit(output);
        long firstWatermark = output.getWatermarks().get(0).getTimestamp();

        output.getWatermarks().clear();

        generator.onEvent("event2", 1000, output);  // Earlier timestamp
        generator.onPeriodicEmit(output);
        long secondWatermark = output.getWatermarks().get(0).getTimestamp();

        // Watermark should not decrease
        assertTrue(secondWatermark >= firstWatermark);
    }

    @Test
    void testVerySmallOutOfOrderness() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofNanos(1));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent("event", 1000, output);
        generator.onPeriodicEmit(output);

        // With 1 nanosecond out of orderness, watermark should be: 1000 - 0 - 1 = 999
        // (since millis truncation)
        assertEquals(999, output.getWatermarks().get(0).getTimestamp());
    }

    @Test
    void testNullEvent() {
        BoundedOutOfOrdernessWatermarkGenerator<String> generator =
                new BoundedOutOfOrdernessWatermarkGenerator<>(Duration.ofMillis(100));

        TestWatermarkOutput output = new TestWatermarkOutput();

        generator.onEvent(null, 1000, output);
        generator.onPeriodicEmit(output);

        // Should handle null event gracefully
        assertEquals(899, output.getWatermarks().get(0).getTimestamp());
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
