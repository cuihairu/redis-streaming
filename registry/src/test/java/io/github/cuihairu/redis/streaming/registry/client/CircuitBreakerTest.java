package io.github.cuihairu.redis.streaming.registry.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CircuitBreaker
 */
class CircuitBreakerTest {

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        // Default config: window=5, threshold=0.5, open=1s, halfOpen=2
        breaker = new CircuitBreaker(5, 0.5, Duration.ofMillis(1000), 2);
    }

    @Test
    void testConstructorWithValidParameters() {
        CircuitBreaker cb = new CircuitBreaker(10, 0.6, Duration.ofMillis(500), 3);

        assertNotNull(cb);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void testConstructorWithEdgeCases() {
        // Zero window size -> clamped to 1
        CircuitBreaker cb1 = new CircuitBreaker(0, 0.5, Duration.ofMillis(1000), 2);
        assertNotNull(cb1);

        // Negative threshold -> clamped to 0.0
        CircuitBreaker cb2 = new CircuitBreaker(5, -0.5, Duration.ofMillis(1000), 2);
        assertNotNull(cb2);

        // Threshold > 1 -> clamped to 1.0
        CircuitBreaker cb3 = new CircuitBreaker(5, 1.5, Duration.ofMillis(1000), 2);
        assertNotNull(cb3);

        // Zero open duration -> clamped to 1ms
        CircuitBreaker cb4 = new CircuitBreaker(5, 0.5, Duration.ZERO, 2);
        assertNotNull(cb4);

        // Zero halfOpenMaxCalls -> clamped to 1
        CircuitBreaker cb5 = new CircuitBreaker(5, 0.5, Duration.ofMillis(1000), 0);
        assertNotNull(cb5);
    }

    @Test
    void testInitialStateIsClosed() {
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void testAllowWhenClosed() {
        assertTrue(breaker.allow());
    }

    @Test
    void testOnSuccessWhenClosed() {
        breaker.onSuccess();

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void testOnFailureWhenClosed() {
        // Record 3 failures out of 5 (60% > 50% threshold)
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();  // 3 failures, 1 success
        breaker.onSuccess();  // 3 failures, 2 success - window size reached
        // At this point: 3/5 = 60% > 50%, should open

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }

    @Test
    void testAllowWhenOpen() {
        // Open the breaker
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onSuccess();

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.allow());
    }

    @Test
    void testOnSuccessInHalfOpen() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(3, 0.5, Duration.ofMillis(100), 2);

        // Open the breaker (3 failures out of 3)
        cb.onFailure();
        cb.onFailure();
        cb.onFailure();

        // Wait for open duration
        Thread.sleep(150);

        // Move to HALF_OPEN
        assertTrue(cb.allow());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // Success should close the breaker
        cb.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // Should allow again
        assertTrue(cb.allow());
    }

    @Test
    void testOnFailureInHalfOpen() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(3, 0.5, Duration.ofMillis(100), 2);

        // Open the breaker
        cb.onFailure();
        cb.onFailure();
        cb.onFailure();

        // Wait for open duration
        Thread.sleep(150);

        // Move to HALF_OPEN
        assertTrue(cb.allow());
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // Failure should open the breaker again
        cb.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Should not allow
        assertFalse(cb.allow());
    }

    @Test
    void testHalfOpenMaxCalls() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(5, 0.5, Duration.ofMillis(100), 2);

        // Open the breaker
        cb.onFailure();
        cb.onFailure();
        cb.onFailure();
        cb.onSuccess();
        cb.onSuccess();

        // Wait for open duration
        Thread.sleep(150);

        // First call allowed
        assertTrue(cb.allow());
        // Second call allowed
        assertTrue(cb.allow());
        // Third call should be blocked (max 2)
        assertFalse(cb.allow());
    }

    @Test
    void testRecoveryFromOpen() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(3, 0.5, Duration.ofMillis(200), 1);

        // Open the breaker
        cb.onFailure();
        cb.onFailure();
        cb.onFailure();

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allow());

        // Wait for open duration
        Thread.sleep(250);

        // Should allow now (HALF_OPEN with maxCalls=1)
        assertTrue(cb.allow());
        assertFalse(cb.allow()); // Second call blocked

        // Success closes it
        cb.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.allow());
    }

    @Test
    void testMultipleFailureRecovery() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, 0.5, Duration.ofMillis(100), 2);

        // First cycle: open -> half-open -> fail -> open
        cb.onFailure();
        cb.onSuccess(); // 1/2 = 50% >= threshold, opens

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        Thread.sleep(150);
        assertTrue(cb.allow()); // HALF_OPEN
        cb.onFailure(); // Back to OPEN

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Second cycle: open -> half-open -> success -> closed
        Thread.sleep(150);
        assertTrue(cb.allow()); // HALF_OPEN
        cb.onSuccess(); // To CLOSED

        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.allow());
    }

    @Test
    void testAllSuccesses() {
        for (int i = 0; i < 20; i++) {
            breaker.onSuccess();
            assertTrue(breaker.allow());
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void testStateTransitions() throws InterruptedException {
        // CLOSED -> OPEN
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onSuccess();
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // OPEN -> HALF_OPEN (after wait)
        Thread.sleep(1100);
        assertTrue(breaker.allow());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.getState());

        // HALF_OPEN -> CLOSED (on success)
        breaker.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void testConcurrentAllow() {
        // Open the breaker
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onSuccess();

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // Multiple calls should all be blocked
        assertFalse(breaker.allow());
        assertFalse(breaker.allow());
        assertFalse(breaker.allow());
    }

    @Test
    void testFailureThresholdZero() {
        // Any single failure should open (with window size 1)
        CircuitBreaker cb = new CircuitBreaker(1, 0.0, Duration.ofMillis(1000), 2);

        cb.onFailure();

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void testSlidingWindowBehavior() {
        CircuitBreaker cb = new CircuitBreaker(3, 0.5, Duration.ofMillis(1000), 2);

        // First window: 2 failures, 1 success -> 2/3 = 66% > 50%, opens
        cb.onFailure();
        cb.onFailure();
        cb.onSuccess();

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }
}
