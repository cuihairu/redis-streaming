package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.client.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerTest {

    @Test
    void testStateTransitions() {
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
        try { Thread.sleep(220); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertTrue(cb.allow(), "half-open allows a single trial call");
        // Fail the trial -> back to OPEN
        cb.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Wait again, then allow single trial and succeed -> CLOSED
        try { Thread.sleep(220); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertTrue(cb.allow());
        cb.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }
}

