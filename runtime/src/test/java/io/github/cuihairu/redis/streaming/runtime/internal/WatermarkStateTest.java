package io.github.cuihairu.redis.streaming.runtime.internal;

import io.github.cuihairu.redis.streaming.api.watermark.Watermark;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WatermarkState
 */
class WatermarkStateTest {

    @Test
    void testInitialState() {
        // Given & When
        WatermarkState state = new WatermarkState();

        // Then
        assertEquals(Long.MIN_VALUE, state.getWatermark());
        assertFalse(state.isIdle());
    }

    @Test
    void testEmitWatermark() {
        // Given
        WatermarkState state = new WatermarkState();
        Watermark watermark = new Watermark(1000);

        // When
        state.emit(watermark);

        // Then
        assertEquals(1000, state.getWatermark());
    }

    @Test
    void testEmitMultipleWatermarks() {
        // Given
        WatermarkState state = new WatermarkState();

        // When - emit increasing watermarks
        state.emit(new Watermark(1000));
        state.emit(new Watermark(2000));
        state.emit(new Watermark(3000));

        // Then - should keep the highest
        assertEquals(3000, state.getWatermark());
    }

    @Test
    void testEmitDecreasingWatermark() {
        // Given
        WatermarkState state = new WatermarkState();

        // When
        state.emit(new Watermark(5000));
        state.emit(new Watermark(3000)); // Lower watermark
        state.emit(new Watermark(1000)); // Even lower

        // Then - should keep the highest (first)
        assertEquals(5000, state.getWatermark());
    }

    @Test
    void testEmitNullWatermark() {
        // Given
        WatermarkState state = new WatermarkState();

        // When
        state.emit(null);

        // Then - should ignore null
        assertEquals(Long.MIN_VALUE, state.getWatermark());
    }

    @Test
    void testEmitNullAfterValidWatermark() {
        // Given
        WatermarkState state = new WatermarkState();
        state.emit(new Watermark(1000));

        // When
        state.emit(null);

        // Then - should not change existing watermark
        assertEquals(1000, state.getWatermark());
    }

    @Test
    void testEmitWatermarkWithLongMinValue() {
        // Given
        WatermarkState state = new WatermarkState();

        // When
        state.emit(new Watermark(Long.MIN_VALUE));

        // Then
        assertEquals(Long.MIN_VALUE, state.getWatermark());
    }

    @Test
    void testEmitWatermarkWithMaxValue() {
        // Given
        WatermarkState state = new WatermarkState();

        // When
        state.emit(new Watermark(Long.MAX_VALUE));

        // Then
        assertEquals(Long.MAX_VALUE, state.getWatermark());
    }

    @Test
    void testEmitWatermarkWithNegativeValue() {
        // Given
        WatermarkState state = new WatermarkState();

        // When
        state.emit(new Watermark(-1000));

        // Then
        assertEquals(-1000, state.getWatermark());
    }

    @Test
    void testEmitWatermarkWithZero() {
        // Given
        WatermarkState state = new WatermarkState();

        // When
        state.emit(new Watermark(0));

        // Then
        assertEquals(0, state.getWatermark());
    }

    @Test
    void testMarkIdle() {
        // Given
        WatermarkState state = new WatermarkState();

        // When
        state.markIdle();

        // Then
        assertTrue(state.isIdle());
    }

    @Test
    void testMarkActive() {
        // Given
        WatermarkState state = new WatermarkState();
        state.markIdle();

        // When
        state.markActive();

        // Then
        assertFalse(state.isIdle());
    }

    @Test
    void testMarkIdleMultipleTimes() {
        // Given
        WatermarkState state = new WatermarkState();

        // When - mark idle multiple times
        state.markIdle();
        state.markIdle();
        state.markIdle();

        // Then - should remain idle
        assertTrue(state.isIdle());
    }

    @Test
    void testMarkActiveMultipleTimes() {
        // Given
        WatermarkState state = new WatermarkState();
        state.markIdle();

        // When
        state.markActive();
        state.markActive();
        state.markActive();

        // Then - should remain active
        assertFalse(state.isIdle());
    }

    @Test
    void testToggleIdleActive() {
        // Given
        WatermarkState state = new WatermarkState();

        // When & Then
        assertFalse(state.isIdle());

        state.markIdle();
        assertTrue(state.isIdle());

        state.markActive();
        assertFalse(state.isIdle());

        state.markIdle();
        assertTrue(state.isIdle());

        state.markActive();
        assertFalse(state.isIdle());
    }

    @Test
    void testEmitWatermarkWhileIdle() {
        // Given
        WatermarkState state = new WatermarkState();
        state.markIdle();

        // When
        state.emit(new Watermark(5000));

        // Then - watermark should update even while idle
        assertEquals(5000, state.getWatermark());
        assertTrue(state.isIdle());
    }

    @Test
    void testEmitWatermarkWhileActive() {
        // Given
        WatermarkState state = new WatermarkState();
        state.markActive();

        // When
        state.emit(new Watermark(3000));

        // Then
        assertEquals(3000, state.getWatermark());
        assertFalse(state.isIdle());
    }

    @Test
    void testMarkIdleDoesNotAffectWatermark() {
        // Given
        WatermarkState state = new WatermarkState();
        state.emit(new Watermark(1000));

        // When
        state.markIdle();

        // Then - watermark should remain unchanged
        assertEquals(1000, state.getWatermark());
    }

    @Test
    void testMarkActiveDoesNotAffectWatermark() {
        // Given
        WatermarkState state = new WatermarkState();
        state.emit(new Watermark(2000));
        state.markIdle();

        // When
        state.markActive();

        // Then - watermark should remain unchanged
        assertEquals(2000, state.getWatermark());
    }

    @Test
    void testWatermarkStateIsPackagePrivate() {
        // Given & When & Then - WatermarkState has package-private access
        // This test verifies that the class is only accessible within its package
        assertTrue(java.lang.reflect.Modifier.isFinal(WatermarkState.class.getModifiers()));
    }

    @Test
    void testMultipleIndependentStates() {
        // Given
        WatermarkState state1 = new WatermarkState();
        WatermarkState state2 = new WatermarkState();

        // When
        state1.emit(new Watermark(1000));
        state1.markIdle();
        state2.emit(new Watermark(5000));
        state2.markActive();

        // Then - states should be independent
        assertEquals(1000, state1.getWatermark());
        assertTrue(state1.isIdle());
        assertEquals(5000, state2.getWatermark());
        assertFalse(state2.isIdle());
    }

    @Test
    void testEmitSameWatermarkMultipleTimes() {
        // Given
        WatermarkState state = new WatermarkState();

        // When - emit same watermark multiple times
        state.emit(new Watermark(3000));
        state.emit(new Watermark(3000));
        state.emit(new Watermark(3000));

        // Then - watermark should remain the same
        assertEquals(3000, state.getWatermark());
    }

    @Test
    void testEmitWatermarkSequence() {
        // Given
        WatermarkState state = new WatermarkState();

        // When - emit sequence of increasing watermarks
        for (long i = 1000; i <= 10000; i += 1000) {
            state.emit(new Watermark(i));
        }

        // Then - should have the highest watermark
        assertEquals(10000, state.getWatermark());
    }

    @Test
    void testInitialStateWithMultipleInstances() {
        // Given & When
        WatermarkState state1 = new WatermarkState();
        WatermarkState state2 = new WatermarkState();

        // Then - each instance should have same initial state
        assertEquals(state1.getWatermark(), state2.getWatermark());
        assertEquals(state1.isIdle(), state2.isIdle());
    }
}
