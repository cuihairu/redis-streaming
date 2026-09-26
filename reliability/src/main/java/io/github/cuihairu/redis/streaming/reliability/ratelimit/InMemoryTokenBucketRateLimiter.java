package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory token bucket rate limiter (per-key).
 *
 * - capacity: maximum burst tokens
 * - ratePerSecond: refill rate (tokens per second)
 *
 * Each key maintains its own bucket state. Fractional tokens are tracked as double to
 * accumulate over time with millisecond resolution.
 *
 * Buckets that are back at full capacity are dropped from the map, so high-cardinality
 * key churn cannot grow the map without bound (B-34). Eviction is semantically
 * transparent: a fully recharged bucket is indistinguishable from an absent one
 * (absent buckets are created full).
 */
public class InMemoryTokenBucketRateLimiter implements RateLimiter {

    private static final int DEFAULT_SWEEP_THRESHOLD = 256;

    private final double capacity;
    private final double ratePerMs;
    /** Time for an emptied bucket to fully recharge, used to pace sweeps. */
    private final long fullRechargeMs;
    private final int sweepThreshold;
    private final AtomicLong lastSweepAtMs = new AtomicLong(0L);

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
        this(capacity, ratePerSecond, DEFAULT_SWEEP_THRESHOLD);
    }

    InMemoryTokenBucketRateLimiter(double capacity, double ratePerSecond, int sweepThreshold) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (ratePerSecond <= 0) throw new IllegalArgumentException("ratePerSecond must be positive");
        if (sweepThreshold < 0) throw new IllegalArgumentException("sweepThreshold must not be negative");
        this.capacity = capacity;
        this.ratePerMs = ratePerSecond / 1000.0d;
        this.fullRechargeMs = (long) Math.ceil(capacity / ratePerSecond * 1000.0d);
        this.sweepThreshold = sweepThreshold;
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
        boolean allowed;
        synchronized (b) {
            // Refill
            long deltaMs = Math.max(0L, nowMillis - b.lastRefillMs);
            if (deltaMs > 0) {
                b.tokens = Math.min(capacity, b.tokens + deltaMs * ratePerMs);
                b.lastRefillMs = nowMillis;
            }
            if (b.tokens >= 1.0d) {
                b.tokens -= 1.0d;
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
        long minGap = Math.max(1_000, fullRechargeMs / 2);
        if (nowMillis - last < minGap || !lastSweepAtMs.compareAndSet(last, nowMillis)) {
            return;
        }
        buckets.entrySet().removeIf(e -> isExpired(e.getValue(), nowMillis));
    }

    private boolean isExpired(Bucket b, long nowMillis) {
        synchronized (b) {
            double refilled = b.tokens + Math.max(0L, nowMillis - b.lastRefillMs) * ratePerMs;
            return refilled >= capacity;
        }
    }
}

