package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryLeakyBucketRateLimiterTest {
    @Test
    void deniesWhenCapacityExceeded_thenRecoversAfterLeak() {
        InMemoryLeakyBucketRateLimiter rl = new InMemoryLeakyBucketRateLimiter(2.0, 1.0); // cap=2, leak 1/s
        String key = "ip:1";
        long t = 1_000_000L;

        assertTrue(rl.allowAt(key, t));   // water=1
        assertTrue(rl.allowAt(key, t));   // water=2
        assertFalse(rl.allowAt(key, t));  // exceed cap

        // after 1s, water drains by 1
        assertTrue(rl.allowAt(key, t + 1000));
        // still near full; next call should likely deny
        assertFalse(rl.allowAt(key, t + 1000));
    }
}

