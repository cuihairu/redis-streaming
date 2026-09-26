package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory leaky bucket rate limiter (per-key).
 *
 * - capacity: max queue (water) before leaking overflows (denies)
 * - leakRatePerSecond: how fast the bucket leaks (drains), tokens per second
 *
 * On each attempt, we drain based on elapsed time, then try to add one unit of water.
 * If capacity would be exceeded, deny.
 *
 * Buckets that are fully drained are dropped from the map, so high-cardinality key
 * churn cannot grow the map without bound (B-34). Eviction is semantically
 * transparent: an empty bucket is indistinguishable from an absent one (absent
 * buckets start empty).
 */
public class InMemoryLeakyBucketRateLimiter implements RateLimiter {

    private static final int DEFAULT_SWEEP_THRESHOLD = 256;

    private final double capacity;
    private final double leakPerMs;
    /** Time for a full bucket to fully drain, used to pace sweeps. */
    private final long fullDrainMs;
    private final int sweepThreshold;
    private final AtomicLong lastSweepAtMs = new AtomicLong(0L);

    private static final class Bucket {
        double water;      // current water level
        long lastMs;       // last update timestamp
    }

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryLeakyBucketRateLimiter(double capacity, double leakRatePerSecond) {
        this(capacity, leakRatePerSecond, DEFAULT_SWEEP_THRESHOLD);
    }

    InMemoryLeakyBucketRateLimiter(double capacity, double leakRatePerSecond, int sweepThreshold) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (leakRatePerSecond <= 0) throw new IllegalArgumentException("leakRatePerSecond must be positive");
        if (sweepThreshold < 0) throw new IllegalArgumentException("sweepThreshold must not be negative");
        this.capacity = capacity;
        this.leakPerMs = leakRatePerSecond / 1000.0d;
        this.fullDrainMs = (long) Math.ceil(capacity / leakRatePerSecond * 1000.0d);
        this.sweepThreshold = sweepThreshold;
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
        boolean allowed;
        synchronized (b) {
            long delta = Math.max(0L, nowMillis - b.lastMs);
            if (delta > 0) {
                b.water = Math.max(0.0d, b.water - delta * leakPerMs);
                b.lastMs = nowMillis;
            }
            if (b.water + 1.0d <= capacity) {
                b.water += 1.0d;
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
        return buckets.size();
    }

    private void maybeSweep(long nowMillis) {
        if (buckets.size() < sweepThreshold) {
            return;
        }
        long last = lastSweepAtMs.get();
        long minGap = Math.max(1_000, fullDrainMs / 2);
        if (nowMillis - last < minGap || !lastSweepAtMs.compareAndSet(last, nowMillis)) {
            return;
        }
        buckets.entrySet().removeIf(e -> isExpired(e.getValue(), nowMillis));
    }

    private boolean isExpired(Bucket b, long nowMillis) {
        synchronized (b) {
            double drained = b.water - Math.max(0L, nowMillis - b.lastMs) * leakPerMs;
            return drained <= 0.0d;
        }
    }
}
