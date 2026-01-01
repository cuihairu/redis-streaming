package io.github.cuihairu.redis.streaming.watermark.generators;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AscendingTimestampWatermarkGenerator
 */
class AscendingTimestampWatermarkGeneratorTest {

    @Test
    void testConstructor() {
        // Given & When
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();

        // Then
        assertEquals(Long.MIN_VALUE, generator.getCurrentWatermark());
    }

    @Test
    void testOnEventWithAscendingTimestamps() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator =
            new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        // When
        generator.onEvent("event1", 1000, output);
        generator.onEvent("event2", 2000, output);
        generator.onEvent("event3", 3000, output);

        // Then - watermark should be highest timestamp minus 1
        assertEquals(2999, generator.getCurrentWatermark());
    }

    @Test
    void testOnEventWithDescendingTimestamps() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();

        // When
        generator.onEvent("event1", 3000, null);
        generator.onEvent("event2", 2000, null);
        generator.onEvent("event3", 1000, null);

        // Then - watermark should not go back
        assertEquals(2999, generator.getCurrentWatermark());
    }

    @Test
    void testOnEventWithSameTimestamps() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();

        // When
        generator.onEvent("event1", 1000, null);
        generator.onEvent("event2", 1000, null);
        generator.onEvent("event3", 1000, null);

        // Then
        assertEquals(999, generator.getCurrentWatermark());
    }

    @Test
    void testOnPeriodicEmit() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();
        generator.onEvent("event", 5000, null);

        // When
        generator.onPeriodicEmit(output);

        // Then
        assertEquals(1, output.getWatermarks().size());
        assertEquals(new Watermark(4999), output.getWatermarks().get(0));
    }

    @Test
    void testOnPeriodicEmitWithNoEvents() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output = new TestWatermarkOutput();

        // When
        generator.onPeriodicEmit(output);

        // Then
        assertEquals(1, output.getWatermarks().size());
        assertEquals(new Watermark(Long.MIN_VALUE), output.getWatermarks().get(0));
    }

    @Test
    void testOnPeriodicEmitMultipleTimes() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();
        TestWatermarkOutput output1 = new TestWatermarkOutput();
        TestWatermarkOutput output2 = new TestWatermarkOutput();
        generator.onEvent("event", 1000, null);

        // When
        generator.onPeriodicEmit(output1);
        generator.onPeriodicEmit(output2);

        // Then
        assertEquals(new Watermark(999), output1.getWatermarks().get(0));
        assertEquals(new Watermark(999), output2.getWatermarks().get(0));
    }

    @Test
    void testGetCurrentWatermarkInitially() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();

        // When
        long watermark = generator.getCurrentWatermark();

        // Then
        assertEquals(Long.MIN_VALUE, watermark);
    }

    @Test
    void testGetCurrentWatermarkAfterEvents() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();
        generator.onEvent("event", 10000, null);

        // When
        long watermark = generator.getCurrentWatermark();

        // Then
        assertEquals(9999, watermark);
    }

    @Test
    void testWithZeroTimestamp() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();

        // When
        generator.onEvent("event", 0, null);

        // Then
        assertEquals(-1, generator.getCurrentWatermark());
    }

    @Test
    void testWithMaxTimestamp() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();

        // When
        generator.onEvent("event", Long.MAX_VALUE, null);

        // Then
        assertEquals(Long.MAX_VALUE - 1, generator.getCurrentWatermark());
    }

    @Test
    void testWithMixedTimestamps() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();

        // When
        generator.onEvent("e1", 1000, null);
        generator.onEvent("e2", 500, null);  // lower, should be ignored
        generator.onEvent("e3", 2000, null); // higher, should update
        generator.onEvent("e4", 1500, null); // lower, should be ignored

        // Then
        assertEquals(1999, generator.getCurrentWatermark());
    }

    @Test
    void testWithNegativeTimestamps() {
        // Given
        AscendingTimestampWatermarkGenerator<String> generator = 
            new AscendingTimestampWatermarkGenerator<>();

        // When
        generator.onEvent("event", -1000, null);

        // Then
        assertEquals(-1001, generator.getCurrentWatermark());
    }

    @Test
    void testMultipleGeneratorsAreIndependent() {
        // Given
        AscendingTimestampWatermarkGenerator<String> gen1 = 
            new AscendingTimestampWatermarkGenerator<>();
        AscendingTimestampWatermarkGenerator<String> gen2 = 
            new AscendingTimestampWatermarkGenerator<>();

        // When
        gen1.onEvent("event", 1000, null);
        gen2.onEvent("event", 2000, null);

        // Then
        assertEquals(999, gen1.getCurrentWatermark());
        assertEquals(1999, gen2.getCurrentWatermark());
    }

    @Test
    void testGenericTypes() {
        // Given & When
        AscendingTimestampWatermarkGenerator<String> stringGen = 
            new AscendingTimestampWatermarkGenerator<>();
        AscendingTimestampWatermarkGenerator<Integer> intGen = 
            new AscendingTimestampWatermarkGenerator<>();
        AscendingTimestampWatermarkGenerator<Object> objectGen = 
            new AscendingTimestampWatermarkGenerator<>();

        // Then - all should work
        stringGen.onEvent("test", 1000, null);
        intGen.onEvent(123, 1000, null);
        objectGen.onEvent(new Object(), 1000, null);
    }

    // Helper class for testing
    private static class TestWatermarkOutput implements WatermarkGenerator.WatermarkOutput {
        private final List<Watermark> watermarks = new ArrayList<>();

        @Override
        public void emitWatermark(Watermark watermark) {
            watermarks.add(watermark);
        }

        @Override
        public void markActive() {
            // No-op for testing
        }

        @Override
        public void markIdle() {
            // No-op for testing
        }

        public List<Watermark> getWatermarks() {
            return watermarks;
        }
    }
}
