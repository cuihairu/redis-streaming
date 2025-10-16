package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory leaky bucket rate limiter (per-key).
 *
 * - capacity: max queue (water) before leaking overflows (denies)
 * - leakRatePerSecond: how fast the bucket leaks (drains), tokens per second
 *
 * On each attempt, we drain based on elapsed time, then try to add one unit of water.
 * If capacity would be exceeded, deny.
 */
public class InMemoryLeakyBucketRateLimiter implements RateLimiter {

    private final double capacity;
    private final double leakPerMs;

    private static final class Bucket {
        double water;      // current water level
        long lastMs;       // last update timestamp
    }

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryLeakyBucketRateLimiter(double capacity, double leakRatePerSecond) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (leakRatePerSecond <= 0) throw new IllegalArgumentException("leakRatePerSecond must be positive");
        this.capacity = capacity;
        this.leakPerMs = leakRatePerSecond / 1000.0d;
    }

    @Override
    public boolean allowAt(String key, long nowMillis) {
        Objects.requireNonNull(key, "key");
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.water = 0.0d;
            nb.lastMs = nowMillis;
            return nb;
        });
        synchronized (b) {
            long delta = Math.max(0L, nowMillis - b.lastMs);
            if (delta > 0) {
                b.water = Math.max(0.0d, b.water - delta * leakPerMs);
                b.lastMs = nowMillis;
            }
            if (b.water + 1.0d <= capacity) {
                b.water += 1.0d;
                return true;
            }
            return false;
        }
    }
}

