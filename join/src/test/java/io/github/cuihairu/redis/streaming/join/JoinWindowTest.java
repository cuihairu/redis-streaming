package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class JoinWindowTest {

    @Test
    void testSymmetricWindow() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(5));

        assertEquals(5000, window.getBeforeMillis());
        assertEquals(5000, window.getAfterMillis());
    }

    @Test
    void testAsymmetricWindow() {
        JoinWindow window = JoinWindow.of(
                Duration.ofSeconds(10),
                Duration.ofSeconds(5)
        );

        assertEquals(10000, window.getBeforeMillis());
        assertEquals(5000, window.getAfterMillis());
    }

    @Test
    void testAfterOnly() {
        JoinWindow window = JoinWindow.afterOnly(Duration.ofSeconds(3));

        assertEquals(0, window.getBeforeMillis());
        assertEquals(3000, window.getAfterMillis());
    }

    @Test
    void testBeforeOnly() {
        JoinWindow window = JoinWindow.beforeOnly(Duration.ofSeconds(3));

        assertEquals(3000, window.getBeforeMillis());
        assertEquals(0, window.getAfterMillis());
    }

    @Test
    void testContainsWithSymmetricWindow() {
        JoinWindow window = JoinWindow.ofSize(Duration.ofSeconds(5));
        long reference = 10000;

        // Within window
        assertTrue(window.contains(reference, 10000)); // exact match
        assertTrue(window.contains(reference, 15000)); // +5s
        assertTrue(window.contains(reference, 5000));  // -5s
        assertTrue(window.contains(reference, 12000)); // +2s
        assertTrue(window.contains(reference, 8000));  // -2s

        // Outside window
        assertFalse(window.contains(reference, 15001)); // +5.001s
        assertFalse(window.contains(reference, 4999));  // -5.001s
        assertFalse(window.contains(reference, 20000)); // +10s
        assertFalse(window.contains(reference, 0));     // -10s
    }

    @Test
    void testContainsWithAfterOnlyWindow() {
        JoinWindow window = JoinWindow.afterOnly(Duration.ofSeconds(5));
        long reference = 10000;

        // Within window
        assertTrue(window.contains(reference, 10000)); // exact match
        assertTrue(window.contains(reference, 15000)); // +5s
        assertTrue(window.contains(reference, 12000)); // +2s

        // Outside window (before)
        assertFalse(window.contains(reference, 9999));  // -0.001s
        assertFalse(window.contains(reference, 5000));  // -5s

        // Outside window (after)
        assertFalse(window.contains(reference, 15001)); // +5.001s
    }

    @Test
    void testContainsWithBeforeOnlyWindow() {
        JoinWindow window = JoinWindow.beforeOnly(Duration.ofSeconds(5));
        long reference = 10000;

        // Within window
        assertTrue(window.contains(reference, 10000)); // exact match
        assertTrue(window.contains(reference, 5000));  // -5s
        assertTrue(window.contains(reference, 8000));  // -2s

        // Outside window (after)
        assertFalse(window.contains(reference, 10001)); // +0.001s
        assertFalse(window.contains(reference, 15000)); // +5s

        // Outside window (before)
        assertFalse(window.contains(reference, 4999));  // -5.001s
    }

    @Test
    void testNegativeDurationThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                JoinWindow.of(Duration.ofSeconds(-1), Duration.ofSeconds(5))
        );

        assertThrows(IllegalArgumentException.class, () ->
                JoinWindow.of(Duration.ofSeconds(5), Duration.ofSeconds(-1))
        );
    }

    @Test
    void testToString() {
        JoinWindow window = JoinWindow.of(Duration.ofSeconds(3), Duration.ofSeconds(5));
        String str = window.toString();

        assertTrue(str.contains("3000"));
        assertTrue(str.contains("5000"));
    }
}
