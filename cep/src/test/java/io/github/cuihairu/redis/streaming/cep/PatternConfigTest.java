package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PatternConfig
 */
class PatternConfigTest {

    private final Pattern<String> testPattern = Pattern.of(s -> true);

    // ===== Builder Tests =====

    @Test
    void testBuilderWithAllFields() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofSeconds(30))
                .contiguous(true)
                .maxSequenceLength(50)
                .allowEventReuse(true)
                .build();

        assertEquals(testPattern, config.getPattern());
        assertEquals(Duration.ofSeconds(30), config.getTimeWindow());
        assertTrue(config.isContiguous());
        assertEquals(50, config.getMaxSequenceLength());
        assertTrue(config.isAllowEventReuse());
    }

    @Test
    void testBuilderWithDefaults() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .build();

        assertEquals(testPattern, config.getPattern());
        assertEquals(Duration.ofMinutes(1), config.getTimeWindow());
        assertFalse(config.isContiguous());
        assertEquals(100, config.getMaxSequenceLength());
        assertFalse(config.isAllowEventReuse());
    }

    @Test
    void testBuilderWithCustomTimeWindow() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofHours(2))
                .build();

        assertEquals(Duration.ofHours(2), config.getTimeWindow());
        assertEquals(2 * 60 * 60 * 1000, config.getTimeWindowMillis());
    }

    // ===== validate Tests =====

    @Test
    void testValidateWithValidConfig() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofSeconds(10))
                .maxSequenceLength(10)
                .build();

        assertDoesNotThrow(config::validate);
    }

    @Test
    void testValidateWithNullPattern() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .timeWindow(Duration.ofSeconds(10))
                .maxSequenceLength(10)
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals("Pattern must be specified", exception.getMessage());
    }

    @Test
    void testValidateWithNullTimeWindow() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(null)
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals("Time window must be positive", exception.getMessage());
    }

    @Test
    void testValidateWithNegativeTimeWindow() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofMillis(-100))
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals("Time window must be positive", exception.getMessage());
    }

    @Test
    void testValidateWithZeroMaxSequenceLength() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .maxSequenceLength(0)
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals("Max sequence length must be positive", exception.getMessage());
    }

    @Test
    void testValidateWithNegativeMaxSequenceLength() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .maxSequenceLength(-10)
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, config::validate);
        assertEquals("Max sequence length must be positive", exception.getMessage());
    }

    // ===== getTimeWindowMillis Tests =====

    @Test
    void testGetTimeWindowMillisWithSeconds() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofSeconds(5))
                .build();

        assertEquals(5000L, config.getTimeWindowMillis());
    }

    @Test
    void testGetTimeWindowMillisWithMinutes() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofMinutes(2))
                .build();

        assertEquals(2 * 60 * 1000L, config.getTimeWindowMillis());
    }

    @Test
    void testGetTimeWindowMillisWithHours() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofHours(1))
                .build();

        assertEquals(60 * 60 * 1000L, config.getTimeWindowMillis());
    }

    @Test
    void testGetTimeWindowMillisWithMillis() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofMillis(250))
                .build();

        assertEquals(250L, config.getTimeWindowMillis());
    }

    @Test
    void testGetTimeWindowMillisWithNanos() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofNanos(1_000_000)) // 1ms
                .build();

        assertEquals(1L, config.getTimeWindowMillis());
    }

    // ===== Edge Cases Tests =====

    @Test
    void testBuilderWithZeroTimeWindow() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ZERO)
                .build();

        assertEquals(Duration.ZERO, config.getTimeWindow());
        assertEquals(0L, config.getTimeWindowMillis());
        // Zero time window passes validation (not negative)
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testBuilderWithLargeMaxSequenceLength() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .maxSequenceLength(1_000_000)
                .build();

        assertEquals(1_000_000, config.getMaxSequenceLength());
        assertDoesNotThrow(config::validate);
    }

    @Test
    void testBuilderWithVerySmallTimeWindow() {
        PatternConfig<String> config = PatternConfig.<String>builder()
                .pattern(testPattern)
                .timeWindow(Duration.ofNanos(1))
                .build();

        assertEquals(Duration.ofNanos(1), config.getTimeWindow());
        assertEquals(0L, config.getTimeWindowMillis()); // < 1ms rounds to 0
        // Nano precision time window should be valid
        assertDoesNotThrow(config::validate);
    }
}
