package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers in-memory limiter constructor validation branches. */
class InMemoryRateLimiterCtorValidationTest {

    @Test
    void slidingWindowRejectsNonPositiveArguments() {
        assertThrows(IllegalArgumentException.class, () -> new InMemorySlidingWindowRateLimiter(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new InMemorySlidingWindowRateLimiter(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new InMemorySlidingWindowRateLimiter(100, 0));
        assertThrows(IllegalArgumentException.class, () -> new InMemorySlidingWindowRateLimiter(100, -2));
    }

    @Test
    void slidingWindowAcceptsValidArguments() {
        InMemorySlidingWindowRateLimiter limiter = new InMemorySlidingWindowRateLimiter(1000, 2);
        assertTrue(limiter.allowAt("k", 1_000));
        assertTrue(limiter.allowAt("k", 1_001));
        assertFalse(limiter.allowAt("k", 1_002));
    }

    @Test
    void leakyBucketRejectsNonPositiveArguments() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryLeakyBucketRateLimiter(0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryLeakyBucketRateLimiter(-1, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryLeakyBucketRateLimiter(2.0, 0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryLeakyBucketRateLimiter(2.0, -0.5));
    }

    @Test
    void leakyBucketAcceptsValidArguments() {
        InMemoryLeakyBucketRateLimiter limiter = new InMemoryLeakyBucketRateLimiter(2.0, 1.0);
        assertTrue(limiter.allowAt("k", 0));
        assertTrue(limiter.allowAt("k", 0));
        assertFalse(limiter.allowAt("k", 0));
    }
}
