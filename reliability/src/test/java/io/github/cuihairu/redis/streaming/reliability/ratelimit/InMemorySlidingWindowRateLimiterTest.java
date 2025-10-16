package io.github.cuihairu.redis.streaming.reliability.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySlidingWindowRateLimiterTest {

    @Test
    void allowsUpToLimitWithinWindow_thenBlocks() {
        InMemorySlidingWindowRateLimiter rl = new InMemorySlidingWindowRateLimiter(1000, 3);
        String key = "userA";
        long t = 1_000_000L;

        assertTrue(rl.allowAt(key, t));
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 20));
        assertFalse(rl.allowAt(key, t + 30)); // 3 within 1s
    }

    @Test
    void evictsOldEntries_allowsAfterWindowSlides() {
        InMemorySlidingWindowRateLimiter rl = new InMemorySlidingWindowRateLimiter(1000, 2);
        String key = "userB";
        long t = 2_000_000L;

        assertTrue(rl.allowAt(key, t));
        assertTrue(rl.allowAt(key, t + 100));
        assertFalse(rl.allowAt(key, t + 200)); // hit limit

        // Slide beyond window; first entry (t) evicted
        assertTrue(rl.allowAt(key, t + 1001));
        // Now we have timestamps at ~t+100 and t+1001 => size 2
        assertFalse(rl.allowAt(key, t + 1002));
    }
}

