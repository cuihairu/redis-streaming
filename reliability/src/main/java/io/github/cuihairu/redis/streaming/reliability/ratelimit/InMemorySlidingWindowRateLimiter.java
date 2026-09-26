package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory sliding window rate limiter (per-key).
 *
 * - Keeps a deque of timestamps (millis) per key
 * - On allowAt(now): evict timestamps older than (now - windowMs), then check {@code size < limit}
 * - If allowed, push now and return true; otherwise false
 *
 * Keys whose whole window has elapsed are dropped from the map, so high-cardinality
 * key churn cannot grow the map without bound (B-34). Eviction is semantically
 * transparent: an expired deque is indistinguishable from an absent one.
 *
 * This implementation is process-local and intended for testing or single-node use.
 */
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    private static final int DEFAULT_SWEEP_THRESHOLD = 256;

    private final long windowMs;
    private final int limit;
    private final int sweepThreshold;
    private final AtomicLong lastSweepAtMs = new AtomicLong(0L);

    private static final class KeyState {
        final Deque<Long> times = new ArrayDeque<>();
    }

    private final ConcurrentMap<String, KeyState> states = new ConcurrentHashMap<>();

    public InMemorySlidingWindowRateLimiter(long windowMs, int limit) {
        this(windowMs, limit, DEFAULT_SWEEP_THRESHOLD);
    }

    InMemorySlidingWindowRateLimiter(long windowMs, int limit, int sweepThreshold) {
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be positive");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        if (sweepThreshold < 0) throw new IllegalArgumentException("sweepThreshold must not be negative");
        this.windowMs = windowMs;
        this.limit = limit;
        this.sweepThreshold = sweepThreshold;
    }

    @Override
    public boolean allowAt(String key, long nowMillis) {
        Objects.requireNonNull(key, "key");
        KeyState st = states.computeIfAbsent(key, k -> new KeyState());
        boolean allowed;
        synchronized (st) {
            long cutoff = nowMillis - windowMs;
            // Evict outdated timestamps
            while (!st.times.isEmpty() && st.times.peekFirst() <= cutoff) {
                st.times.removeFirst();
            }
            if (st.times.size() < limit) {
                st.times.addLast(nowMillis);
                allowed = true;
            } else {
                allowed = false;
            }
        }
        maybeSweep(nowMillis);
        return allowed;
    }

    /**
     * Number of keys currently tracked (for monitoring and tests).
     */
    public int trackedKeyCount() {
        return states.size();
    }

    private void maybeSweep(long nowMillis) {
        if (states.size() < sweepThreshold) {
            return;
        }
        long last = lastSweepAtMs.get();
        long minGap = Math.max(1_000, windowMs / 2);
        if (nowMillis - last < minGap || !lastSweepAtMs.compareAndSet(last, nowMillis)) {
            return;
        }
        states.entrySet().removeIf(e -> isExpired(e.getValue(), nowMillis));
    }

    private boolean isExpired(KeyState st, long nowMillis) {
        synchronized (st) {
            Long newest = st.times.peekLast();
            return newest != null && newest <= nowMillis - windowMs;
        }
    }
}
