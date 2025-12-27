package io.github.cuihairu.redis.streaming.registry.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ChangeThresholdTest {

    @Test
    public void testAnySignificantOnChange() {
        ChangeThreshold t = new ChangeThreshold(0, ChangeThresholdType.ANY);
        assertFalse(t.isSignificant("a", "a"));
        assertTrue(t.isSignificant("a", "b"));
        assertTrue(t.isSignificant(null, "b"));
    }

    @Test
    public void testNullValuesAreSignificant() {
        ChangeThreshold t = new ChangeThreshold(10, ChangeThresholdType.ABSOLUTE);
        assertTrue(t.isSignificant(null, 1));
        assertTrue(t.isSignificant(1, null));
    }

    @Test
    public void testNonNumericFallsBackToEquals() {
        ChangeThreshold t = new ChangeThreshold(10, ChangeThresholdType.PERCENTAGE);
        assertFalse(t.isSignificant("a", "a"));
        assertTrue(t.isSignificant("a", "b"));
    }

    @Test
    public void testPercentageChange() {
        ChangeThreshold t = new ChangeThreshold(10, ChangeThresholdType.PERCENTAGE);
        assertFalse(t.isSignificant(100, 109)); // 9%
        assertTrue(t.isSignificant(100, 111));  // 11%
    }

    @Test
    public void testPercentageWhenOldIsZero() {
        ChangeThreshold t = new ChangeThreshold(10, ChangeThresholdType.PERCENTAGE);
        assertFalse(t.isSignificant(0, 0));
        assertTrue(t.isSignificant(0, 1));
    }

    @Test
    public void testAbsoluteChange() {
        ChangeThreshold t = new ChangeThreshold(5, ChangeThresholdType.ABSOLUTE);
        assertFalse(t.isSignificant(100, 104));
        assertTrue(t.isSignificant(100, 106));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testDeprecatedConstructorUsesFromValue() {
        ChangeThreshold t = new ChangeThreshold(5, "absolute");
        assertEquals(ChangeThresholdType.ABSOLUTE, t.getType());
        assertTrue(t.isSignificant(1, 10));
    }
}
