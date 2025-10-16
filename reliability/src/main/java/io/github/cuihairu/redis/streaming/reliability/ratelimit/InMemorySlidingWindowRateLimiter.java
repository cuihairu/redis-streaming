package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory sliding window rate limiter (per-key).
 *
 * - Keeps a deque of timestamps (millis) per key
 * - On allowAt(now): evict timestamps older than (now - windowMs), then check {@code size < limit}
 * - If allowed, push now and return true; otherwise false
 *
 * This implementation is process-local and intended for testing or single-node use.
 */
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    private final long windowMs;
    private final int limit;

    private static final class KeyState {
        final Deque<Long> times = new ArrayDeque<>();
    }

    private final ConcurrentMap<String, KeyState> states = new ConcurrentHashMap<>();

    public InMemorySlidingWindowRateLimiter(long windowMs, int limit) {
        if (windowMs <= 0) throw new IllegalArgumentException("windowMs must be positive");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        this.windowMs = windowMs;
        this.limit = limit;
    }

    @Override
    public boolean allowAt(String key, long nowMillis) {
        Objects.requireNonNull(key, "key");
        KeyState st = states.computeIfAbsent(key, k -> new KeyState());
        synchronized (st) {
            long cutoff = nowMillis - windowMs;
            // Evict outdated timestamps
            while (!st.times.isEmpty() && st.times.peekFirst() <= cutoff) {
                st.times.removeFirst();
            }
            if (st.times.size() < limit) {
                st.times.addLast(nowMillis);
                return true;
            }
            return false;
        }
    }
}
