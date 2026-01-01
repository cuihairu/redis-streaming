package io.github.cuihairu.redis.streaming.watermark;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import io.github.cuihairu.redis.streaming.api.watermark.WatermarkGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WatermarkStrategy
 */
class WatermarkStrategyTest {

    @Test
    void testForMonotonousTimestamps() {
        // Given & When
        WatermarkStrategy<String> strategy = WatermarkStrategy.forMonotonousTimestamps();

        // Then
        assertNotNull(strategy);
        assertNotNull(strategy.createWatermarkGenerator());
        assertNull(strategy.getTimestampAssigner());
    }

    @Test
    void testForBoundedOutOfOrderness() {
        // Given
        Duration maxOutOfOrderness = Duration.ofSeconds(5);

        // When
        WatermarkStrategy<String> strategy = WatermarkStrategy.forBoundedOutOfOrderness(maxOutOfOrderness);

        // Then
        assertNotNull(strategy);
        assertNotNull(strategy.createWatermarkGenerator());
        assertNull(strategy.getTimestampAssigner());
    }

    @Test
    void testForGenerator() {
        // Given
        Supplier<WatermarkGenerator<String>> supplier = () -> new WatermarkGenerator<String>() {
            @Override
            public void onEvent(String event, long eventTimestamp, WatermarkOutput output) {
                // No-op
            }

            @Override
            public void onPeriodicEmit(WatermarkOutput output) {
                // No-op
            }
        };

        // When
        WatermarkStrategy<String> strategy = WatermarkStrategy.forGenerator(supplier);

        // Then
        assertNotNull(strategy);
        assertNotNull(strategy.createWatermarkGenerator());
        assertNull(strategy.getTimestampAssigner());
    }

    @Test
    void testNoWatermarks() {
        // Given & When
        WatermarkStrategy<String> strategy = WatermarkStrategy.noWatermarks();

        // Then
        assertNotNull(strategy);
        assertNotNull(strategy.createWatermarkGenerator());
        assertNull(strategy.getTimestampAssigner());
    }

    @Test
    void testWithTimestampAssigner() {
        // Given
        WatermarkStrategy<String> baseStrategy = WatermarkStrategy.forMonotonousTimestamps();
        WatermarkStrategy.TimestampAssigner<String> assigner = (event, timestamp) -> 12345L;

        // When
        WatermarkStrategy<String> strategy = baseStrategy.withTimestampAssigner(assigner);

        // Then
        assertNotNull(strategy);
        assertNotNull(strategy.getTimestampAssigner());
        assertEquals(assigner, strategy.getTimestampAssigner());
    }

    @Test
    void testCreateWatermarkGenerator() {
        // Given
        WatermarkStrategy<String> strategy = WatermarkStrategy.forMonotonousTimestamps();

        // When
        WatermarkGenerator<String> generator1 = strategy.createWatermarkGenerator();
        WatermarkGenerator<String> generator2 = strategy.createWatermarkGenerator();

        // Then - should create new instances each time
        assertNotNull(generator1);
        assertNotNull(generator2);
        assertNotSame(generator1, generator2);
    }

    @Test
    void testExtractTimestampWithAssigner() {
        // Given
        WatermarkStrategy<Long> strategy = WatermarkStrategy.<Long>forMonotonousTimestamps()
                .withTimestampAssigner((event, timestamp) -> event * 2);
        Long event = 100L;
        long recordTimestamp = 999L;

        // When
        long extractedTimestamp = strategy.extractTimestamp(event, recordTimestamp);

        // Then
        assertEquals(200L, extractedTimestamp);
    }

    @Test
    void testExtractTimestampWithoutAssigner() {
        // Given
        WatermarkStrategy<String> strategy = WatermarkStrategy.forMonotonousTimestamps();
        String event = "test";
        long recordTimestamp = 5000L;

        // When
        long extractedTimestamp = strategy.extractTimestamp(event, recordTimestamp);

        // Then
        assertEquals(recordTimestamp, extractedTimestamp);
    }

    @Test
    void testExtractTimestampWithNullAssigner() {
        // Given
        WatermarkStrategy<String> strategy = WatermarkStrategy.forMonotonousTimestamps();
        String event = "test";
        long recordTimestamp = 3000L;

        // When
        long extractedTimestamp = strategy.extractTimestamp(event, recordTimestamp);

        // Then
        assertEquals(recordTimestamp, extractedTimestamp);
    }

    @Test
    void testWithTimestampAssignerChaining() {
        // Given
        WatermarkStrategy<String> strategy1 = WatermarkStrategy.<String>noWatermarks();
        WatermarkStrategy.TimestampAssigner<String> assigner1 = (event, timestamp) -> 100L;
        WatermarkStrategy.TimestampAssigner<String> assigner2 = (event, timestamp) -> 200L;

        // When
        WatermarkStrategy<String> strategy2 = strategy1.withTimestampAssigner(assigner1);
        WatermarkStrategy<String> strategy3 = strategy2.withTimestampAssigner(assigner2);

        // Then - last assigner wins
        assertEquals(200L, strategy3.extractTimestamp("test", 0));
    }

    @Test
    void testForBoundedOutOfOrdernessWithZeroDuration() {
        // Given
        Duration maxOutOfOrderness = Duration.ZERO;

        // When
        WatermarkStrategy<String> strategy = WatermarkStrategy.forBoundedOutOfOrderness(maxOutOfOrderness);

        // Then
        assertNotNull(strategy);
    }

    @Test
    void testForBoundedOutOfOrdernessWithNegativeDuration() {
        // Given
        Duration maxOutOfOrderness = Duration.ofMillis(-100);

        // When
        WatermarkStrategy<String> strategy = WatermarkStrategy.forBoundedOutOfOrderness(maxOutOfOrderness);

        // Then
        assertNotNull(strategy);
    }

    @Test
    void testForBoundedOutOfOrdernessWithLargeDuration() {
        // Given
        Duration maxOutOfOrderness = Duration.ofHours(24);

        // When
        WatermarkStrategy<String> strategy = WatermarkStrategy.forBoundedOutOfOrderness(maxOutOfOrderness);

        // Then
        assertNotNull(strategy);
    }

    @Test
    void testGetTimestampAssignerReturnsNullForBaseStrategy() {
        // Given
        WatermarkStrategy<String> strategy = WatermarkStrategy.forMonotonousTimestamps();

        // When
        var assigner = strategy.getTimestampAssigner();

        // Then
        assertNull(assigner);
    }

    @Test
    void testGenericTypes() {
        // Given & When
        WatermarkStrategy<String> stringStrategy = WatermarkStrategy.<String>forMonotonousTimestamps();
        WatermarkStrategy<Integer> intStrategy = WatermarkStrategy.<Integer>forMonotonousTimestamps();
        WatermarkStrategy<Object> objectStrategy = WatermarkStrategy.<Object>forMonotonousTimestamps();

        // Then
        assertNotNull(stringStrategy);
        assertNotNull(intStrategy);
        assertNotNull(objectStrategy);
    }

    @Test
    void testIsSerializable() {
        // Given
        WatermarkStrategy<String> strategy = WatermarkStrategy.forMonotonousTimestamps();

        // When & Then
        assertTrue(strategy instanceof java.io.Serializable);
    }

    @Test
    void testTimestampAssignerFunctionalInterface() {
        // Given
        WatermarkStrategy<String> strategy = WatermarkStrategy.<String>forMonotonousTimestamps()
                .withTimestampAssigner((event, timestamp) -> {
                    // Custom logic
                    return event.length() * 1000L;
                });

        // When
        long timestamp1 = strategy.extractTimestamp("abc", 0);
        long timestamp2 = strategy.extractTimestamp("abcd", 0);

        // Then
        assertEquals(3000L, timestamp1);
        assertEquals(4000L, timestamp2);
    }

    @Test
    void testMultipleGeneratorsAreIndependent() {
        // Given
        WatermarkStrategy<String> strategy = WatermarkStrategy.forMonotonousTimestamps();

        // When
        WatermarkGenerator<String> gen1 = strategy.createWatermarkGenerator();
        WatermarkGenerator<String> gen2 = strategy.createWatermarkGenerator();

        // Then - each generator should be independent
        assertNotSame(gen1, gen2);
    }
}
