package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTokenBucketRateLimiterTest {
    @Test
    void allowsBurstUpToCapacity_thenRateLimited() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(3, 1.0); // cap=3, 1/s
        String key = "userC";
        long t = 3_000_000L;

        // consume initial full bucket
        assertTrue(rl.allowAt(key, t));
        assertTrue(rl.allowAt(key, t));
        assertTrue(rl.allowAt(key, t));
        assertFalse(rl.allowAt(key, t));

        // after 1s, one token refilled
        assertTrue(rl.allowAt(key, t + 1000));
        assertFalse(rl.allowAt(key, t + 1000));
    }
}

