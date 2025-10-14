package io.github.cuihairu.redis.streaming.registry.client;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal circuit breaker with CLOSED -> OPEN -> HALF_OPEN transitions.
 */
public class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int windowSize;
    private final double failureThreshold; // 0..1
    private final long openDurationMs;
    private final int halfOpenMaxCalls;

    private volatile State state = State.CLOSED;
    private volatile long openUntil = 0;

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger halfOpenCalls = new AtomicInteger();

    public CircuitBreaker(int windowSize, double failureThreshold, Duration openDuration, int halfOpenMaxCalls) {
        this.windowSize = Math.max(1, windowSize);
        this.failureThreshold = Math.min(1.0, Math.max(0.0, failureThreshold));
        this.openDurationMs = Math.max(1, openDuration.toMillis());
        this.halfOpenMaxCalls = Math.max(1, halfOpenMaxCalls);
    }

    public synchronized boolean allow() {
        long now = System.currentTimeMillis();
        if (state == State.OPEN) {
            if (now >= openUntil) {
                state = State.HALF_OPEN;
                halfOpenCalls.set(0);
            } else {
                return false;
            }
        }
        if (state == State.HALF_OPEN) {
            return halfOpenCalls.incrementAndGet() <= halfOpenMaxCalls;
        }
        return true;
    }

    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            // any success closes breaker and resets stats
            toClosed();
            return;
        }
        slideWindow(false);
    }

    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            // fail fast back to OPEN
            toOpen();
            return;
        }
        if (slideWindow(true) >= failureThreshold) {
            toOpen();
        }
    }

    private double slideWindow(boolean failed) {
        int c = calls.incrementAndGet();
        if (failed) failures.incrementAndGet();
        // simple bounded window by resetting on overflow
        if (c >= windowSize) {
            double rate = failures.get() / (double) c;
            calls.set(0);
            failures.set(0);
            return rate;
        }
        return failures.get() / (double) Math.max(1, c);
    }

    private void toOpen() {
        state = State.OPEN;
        openUntil = System.currentTimeMillis() + openDurationMs;
        calls.set(0);
        failures.set(0);
    }

    private void toClosed() {
        state = State.CLOSED;
        openUntil = 0;
        calls.set(0);
        failures.set(0);
    }

    public State getState() { return state; }
}

