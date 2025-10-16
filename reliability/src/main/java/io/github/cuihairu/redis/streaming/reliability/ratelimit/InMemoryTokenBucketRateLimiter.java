package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory token bucket rate limiter (per-key).
 *
 * - capacity: maximum burst tokens
 * - ratePerSecond: refill rate (tokens per second)
 *
 * Each key maintains its own bucket state. Fractional tokens are tracked as double to
 * accumulate over time with millisecond resolution.
 */
public class InMemoryTokenBucketRateLimiter implements RateLimiter {

    private final double capacity;
    private final double ratePerMs;

    private static final class Bucket {
        double tokens;      // current tokens (can be fractional)
        long lastRefillMs;  // last refill timestamp
    }

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param capacity maximum tokens (burst)
     * @param ratePerSecond refill rate (tokens per second)
     */
    public InMemoryTokenBucketRateLimiter(double capacity, double ratePerSecond) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (ratePerSecond <= 0) throw new IllegalArgumentException("ratePerSecond must be positive");
        this.capacity = capacity;
        this.ratePerMs = ratePerSecond / 1000.0d;
    }

    @Override
    public boolean allowAt(String key, long nowMillis) {
        Objects.requireNonNull(key, "key");
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.tokens = capacity; // start full to allow initial burst
            nb.lastRefillMs = nowMillis;
            return nb;
        });
        synchronized (b) {
            // Refill
            long deltaMs = Math.max(0L, nowMillis - b.lastRefillMs);
            if (deltaMs > 0) {
                b.tokens = Math.min(capacity, b.tokens + deltaMs * ratePerMs);
                b.lastRefillMs = nowMillis;
            }
            if (b.tokens >= 1.0d) {
                b.tokens -= 1.0d;
                return true;
            }
            return false;
        }
    }
}

