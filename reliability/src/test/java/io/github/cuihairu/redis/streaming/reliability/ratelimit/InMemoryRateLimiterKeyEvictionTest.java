package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-34 regression: the in-memory rate limiters kept per-key state forever, so
 * high-cardinality key churn (IPs, users) grew the internal map without bound.
 * Keys whose state became semantically indistinguishable from "absent" must be
 * evicted: fully-elapsed windows, fully-recharged token buckets, fully-drained
 * leaky buckets.
 *
 * <p>The map size is observed via reflection on the private field (named identically
 * before and after the fix) so the file compiles against and reproduces on the
 * pre-fix code, which never evicts.
 */
class InMemoryRateLimiterKeyEvictionTest {

    private static final int CHURN_KEYS = 300;

    private static int mapSize(Object limiter, String fieldName) {
        try {
            Field f = limiter.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return ((Map<?, ?>) f.get(limiter)).size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void slidingWindowEvictsKeysWhoseWindowFullyElapsed() {
        InMemorySlidingWindowRateLimiter limiter = new InMemorySlidingWindowRateLimiter(1_000, 10);

        for (int i = 0; i < CHURN_KEYS; i++) {
            assertTrue(limiter.allowAt("k" + i, 10_000));
        }
        assertEquals(CHURN_KEYS, mapSize(limiter, "states"));

        // every old key's window elapsed; the next sight crosses the sweep threshold
        // and drops them all (the sweep's minimum gap does not gate the first sweep)
        assertTrue(limiter.allowAt("fresh", 12_001));
        assertEquals(1, mapSize(limiter, "states"),
                "expired keys must be evicted (old code kept all " + (CHURN_KEYS + 1) + ")");
    }

    @Test
    void slidingWindowKeepsActiveKeys() {
        InMemorySlidingWindowRateLimiter limiter = new InMemorySlidingWindowRateLimiter(1_000, 10);

        for (int i = 0; i < CHURN_KEYS; i++) {
            assertTrue(limiter.allowAt("k" + i, 10_000));
        }
        // a sweep fires (size above threshold) but the keys are all inside the window
        assertTrue(limiter.allowAt("k0", 10_001));
        assertEquals(CHURN_KEYS, mapSize(limiter, "states"),
                "an in-window key must never be evicted");
    }

    @Test
    void tokenBucketEvictsFullyRechargedBuckets() {
        // 1000 tokens/s = 1 per ms: a bucket after one allowance recharges in ~1ms
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(2, 1000);

        for (int i = 0; i < CHURN_KEYS; i++) {
            assertTrue(limiter.allowAt("k" + i, 10_000));
        }
        assertEquals(CHURN_KEYS, mapSize(limiter, "buckets"));

        assertTrue(limiter.allowAt("fresh", 11_010));
        assertEquals(1, mapSize(limiter, "buckets"),
                "recharged buckets are indistinguishable from absent ones and must be evicted");
    }

    @Test
    void tokenBucketKeepsDepletedBuckets() {
        // slow refill: 1 token/s, so a bucket after one allowance stays visibly depleted
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter(2, 1);

        for (int i = 0; i < CHURN_KEYS; i++) {
            assertTrue(limiter.allowAt("k" + i, 10_000));
        }
        assertTrue(limiter.allowAt("k0", 10_001));
        assertEquals(CHURN_KEYS, mapSize(limiter, "buckets"),
                "a depleted bucket must not be evicted");
    }

    @Test
    void leakyBucketEvictsFullyDrainedBuckets() {
        // 1000 units/s = 1 per ms: a bucket after one allowance drains in ~1ms
        InMemoryLeakyBucketRateLimiter limiter = new InMemoryLeakyBucketRateLimiter(2, 1000);

        for (int i = 0; i < CHURN_KEYS; i++) {
            assertTrue(limiter.allowAt("k" + i, 10_000));
        }
        assertEquals(CHURN_KEYS, mapSize(limiter, "buckets"));

        assertTrue(limiter.allowAt("fresh", 11_010));
        assertEquals(1, mapSize(limiter, "buckets"),
                "drained buckets are indistinguishable from absent ones and must be evicted");
    }

    @Test
    void leakyBucketKeepsNonEmptyBuckets() {
        // slow leak: 1 unit/s, so a bucket after one allowance stays visibly non-empty
        InMemoryLeakyBucketRateLimiter limiter = new InMemoryLeakyBucketRateLimiter(2, 1);

        for (int i = 0; i < CHURN_KEYS; i++) {
            assertTrue(limiter.allowAt("k" + i, 10_000));
        }
        assertTrue(limiter.allowAt("k0", 10_001));
        assertEquals(CHURN_KEYS, mapSize(limiter, "buckets"),
                "a non-empty bucket must not be evicted");
    }

    @Test
    void evictedKeysBehaveLikeFreshKeysOnTheirNextSight() {
        InMemorySlidingWindowRateLimiter sliding = new InMemorySlidingWindowRateLimiter(1_000, 1);
        assertTrue(sliding.allowAt("a", 10_000));
        assertFalse(sliding.allowAt("a", 10_001), "limit 1: second sight inside the window is denied");
        assertTrue(sliding.allowAt("a", 11_001), "after the window elapsed the key allows again");

        // 1000 tokens/s = 1 per ms
        InMemoryTokenBucketRateLimiter token = new InMemoryTokenBucketRateLimiter(2, 1000);
        assertTrue(token.allowAt("a", 10_000));
        assertTrue(token.allowAt("a", 10_000));
        assertFalse(token.allowAt("a", 10_000), "empty bucket denies");
        assertTrue(token.allowAt("a", 10_002), "fully recharged (2ms x 1/ms) bucket allows again");

        // 1000 units/s = 1 per ms
        InMemoryLeakyBucketRateLimiter leaky = new InMemoryLeakyBucketRateLimiter(2, 1000);
        assertTrue(leaky.allowAt("a", 10_000));
        assertTrue(leaky.allowAt("a", 10_000));
        assertFalse(leaky.allowAt("a", 10_000), "full bucket denies");
        assertTrue(leaky.allowAt("a", 10_002), "fully drained bucket allows again");
    }
}
