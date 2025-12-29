package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.client.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerTest {

    @Test
    void testStateTransitions() throws InterruptedException {
        // windowSize=4, threshold=0.5, openDuration=200ms, halfOpenMaxCalls=1
        CircuitBreaker cb = new CircuitBreaker(4, 0.5, Duration.ofMillis(200), 1);

        // First success keeps CLOSED
        assertTrue(cb.allow());
        cb.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // Fail once -> ratio 1/1 >= 0.5, should OPEN immediately per implementation
        assertTrue(cb.allow());
        cb.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // While OPEN, allow() should return false
        assertFalse(cb.allow());

        // Wait to HALF_OPEN
        Thread.sleep(220);
        assertTrue(cb.allow(), "half-open allows a single trial call");
        // Fail the trial -> back to OPEN
        cb.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Wait again, then allow single trial and succeed -> CLOSED
        Thread.sleep(220);
        assertTrue(cb.allow());
        cb.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void testAllowInClosedState() {
        CircuitBreaker cb = new CircuitBreaker(10, 0.5, Duration.ofMillis(200), 1);
        for (int i = 0; i < 100; i++) {
            assertTrue(cb.allow(), "Should always allow in CLOSED state");
        }
    }

    @Test
    void testAllowInOpenState() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, 0.5, Duration.ofMillis(5000), 1);
        // Trigger OPEN state
        cb.onFailure();
        cb.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Should not allow while OPEN
        assertFalse(cb.allow());
        assertFalse(cb.allow());
        assertFalse(cb.allow());
    }

    @Test
    void testHalfOpenWithMultipleCalls() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, 0.5, Duration.ofMillis(100), 3);

        // Trigger OPEN
        cb.onFailure();
        cb.onFailure();

        // Wait for HALF_OPEN
        Thread.sleep(120);

        // Should allow up to halfOpenMaxCalls
        assertTrue(cb.allow(), "First HALF_OPEN call should be allowed");
        assertTrue(cb.allow(), "Second HALF_OPEN call should be allowed");
        assertTrue(cb.allow(), "Third HALF_OPEN call should be allowed");
        assertFalse(cb.allow(), "Fourth call should be rejected in HALF_OPEN");
    }

    @Test
    void testMultipleSuccessesKeepClosed() {
        CircuitBreaker cb = new CircuitBreaker(10, 0.5, Duration.ofMillis(200), 1);

        for (int i = 0; i < 50; i++) {
            assertTrue(cb.allow());
            cb.onSuccess();
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void testRecoveryFromHalfOpen() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, 0.5, Duration.ofMillis(100), 2);

        // Trigger OPEN
        cb.onFailure();
        cb.onFailure();

        // Wait for HALF_OPEN
        Thread.sleep(120);

        // Succeed in HALF_OPEN -> should close
        assertTrue(cb.allow());
        cb.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // Window is reset, need 2 failures to open again
        cb.onFailure();
        cb.onFailure();

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void testOnSuccessInClosedState() {
        CircuitBreaker cb = new CircuitBreaker(10, 0.5, Duration.ofMillis(200), 1);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        cb.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void testGetState() {
        CircuitBreaker cb = new CircuitBreaker(10, 0.5, Duration.ofMillis(200), 1);

        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // Trigger OPEN
        for (int i = 0; i < 5; i++) {
            cb.onFailure();
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void testZeroOpenDurationTransitionsImmediately() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, 0.5, Duration.ofMillis(0), 1);

        // Trigger OPEN
        cb.onFailure();
        cb.onFailure();

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // With 0ms duration (normalized to 1ms), should transition to HALF_OPEN quickly
        Thread.sleep(10);

        assertTrue(cb.allow(), "Should transition to HALF_OPEN after openDuration");
    }

    @Test
    void testMultipleConsecutiveFailuresOpenCircuit() {
        CircuitBreaker cb = new CircuitBreaker(5, 0.5, Duration.ofMillis(5000), 1);

        // Need 3 failures out of 5 to reach 60% > 50%
        for (int i = 0; i < 3; i++) {
            cb.onFailure();
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allow());
    }

    @Test
    void testSuccessResetsFailureCount() {
        CircuitBreaker cb = new CircuitBreaker(10, 0.5, Duration.ofMillis(5000), 1);

        // Mix of successes and failures
        for (int i = 0; i < 4; i++) {
            cb.onFailure();
        }
        cb.onSuccess(); // Resets window

        // Now need fresh failures to trigger open
        for (int i = 0; i < 5; i++) {
            cb.onFailure();
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }
}

