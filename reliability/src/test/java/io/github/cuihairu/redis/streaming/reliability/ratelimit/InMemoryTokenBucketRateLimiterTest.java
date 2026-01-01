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

    @Test
    void constructorThrowsExceptionForNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () ->
                new InMemoryTokenBucketRateLimiter(0, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new InMemoryTokenBucketRateLimiter(-1, 1.0));
    }

    @Test
    void constructorThrowsExceptionForNonPositiveRate() {
        assertThrows(IllegalArgumentException.class, () ->
                new InMemoryTokenBucketRateLimiter(10, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new InMemoryTokenBucketRateLimiter(10, -1.0));
    }

    @Test
    void allowAtThrowsExceptionForNullKey() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(10, 1.0);
        assertThrows(NullPointerException.class, () ->
                rl.allowAt(null, 1000));
    }

    @Test
    void allowAtWithEmptyKey() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(2, 1.0);
        assertTrue(rl.allowAt("", 1000));
        assertTrue(rl.allowAt("", 1000));
        assertFalse(rl.allowAt("", 1000));
    }

    @Test
    void multipleKeysIndependent() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(2, 1.0);
        long t = 1000;

        assertTrue(rl.allowAt("user1", t));
        assertTrue(rl.allowAt("user1", t));
        assertFalse(rl.allowAt("user1", t));

        // user2 should have its own bucket
        assertTrue(rl.allowAt("user2", t));
        assertTrue(rl.allowAt("user2", t));
        assertFalse(rl.allowAt("user2", t));
    }

    @Test
    void tokenRefillOverTime() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(10, 5.0); // 5 tokens/s
        String key = "user";
        long t = 1000;

        // Consume all tokens
        for (int i = 0; i < 10; i++) {
            assertTrue(rl.allowAt(key, t));
        }
        assertFalse(rl.allowAt(key, t));

        // After 200ms, should have 1 token (5 * 0.2 = 1)
        assertTrue(rl.allowAt(key, t + 200));
        assertFalse(rl.allowAt(key, t + 200));

        // After another 400ms (total 600ms), should have 2 more tokens (5 * 0.4 = 2)
        assertTrue(rl.allowAt(key, t + 600));
        assertTrue(rl.allowAt(key, t + 600));
        assertFalse(rl.allowAt(key, t + 600));
    }

    @Test
    void fractionalTokensAccumulate() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(10, 1.0); // 1 token/s
        String key = "user";
        long t = 1000;

        // Consume all tokens
        for (int i = 0; i < 10; i++) {
            assertTrue(rl.allowAt(key, t));
        }
        assertFalse(rl.allowAt(key, t));

        // After 500ms, should have 0.5 tokens (not enough)
        assertFalse(rl.allowAt(key, t + 500));

        // After another 500ms (total 1s), should have 1 token
        assertTrue(rl.allowAt(key, t + 1000));
        assertFalse(rl.allowAt(key, t + 1000));
    }

    @Test
    void tokensDoNotExceedCapacity() throws InterruptedException {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(5, 10.0); // 10 tokens/s
        String key = "user";
        long t = 1000;

        // Consume all tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.allowAt(key, t));
        }

        // Wait 1 second - should get 10 tokens but capped at 5
        Thread.sleep(1000);
        long t2 = System.currentTimeMillis();

        // Should only be able to consume 5 (capacity), not 10
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.allowAt(key, t2));
        }
        assertFalse(rl.allowAt(key, t2));
    }

    @Test
    void allowAtWithTimestampGoingBackwards() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(10, 1.0);
        String key = "user";
        long t = 5000;

        // Consume some tokens
        assertTrue(rl.allowAt(key, t));
        assertTrue(rl.allowAt(key, t));

        // Call with earlier timestamp (should not refill)
        assertTrue(rl.allowAt(key, t - 1000));
        assertTrue(rl.allowAt(key, t - 1000));
        assertTrue(rl.allowAt(key, t - 1000));
        // Should have same number of tokens as before (no refill due to backwards time)
    }

    @Test
    void allowAtWithZeroTimestamp() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(5, 1.0);
        String key = "user";

        assertTrue(rl.allowAt(key, 0));
        assertTrue(rl.allowAt(key, 0));
        assertTrue(rl.allowAt(key, 0));
        assertTrue(rl.allowAt(key, 0));
        assertTrue(rl.allowAt(key, 0));
        assertFalse(rl.allowAt(key, 0));
    }

    @Test
    void highRatePerSecond() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(100, 1000.0); // 1000 tokens/s
        String key = "user";
        long t = 1000;

        // Consume all
        for (int i = 0; i < 100; i++) {
            assertTrue(rl.allowAt(key, t));
        }
        assertFalse(rl.allowAt(key, t));

        // After 10ms, should have 10 tokens (1000 * 0.01 = 10)
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 10));
        assertTrue(rl.allowAt(key, t + 10));
        assertFalse(rl.allowAt(key, t + 10));
    }

    @Test
    void lowRatePerSecond() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(5, 0.1); // 0.1 tokens/s
        String key = "user";
        long t = 1000;

        // Consume all tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.allowAt(key, t));
        }
        assertFalse(rl.allowAt(key, t));

        // After 5 seconds, should have 0.5 tokens (not enough)
        assertFalse(rl.allowAt(key, t + 5000));

        // After another 5 seconds (total 10s), should have 1 token
        assertTrue(rl.allowAt(key, t + 10000));
        assertFalse(rl.allowAt(key, t + 10000));
    }

    @Test
    void capacityOfOne() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(1, 1.0);
        String key = "user";
        long t = 1000;

        assertTrue(rl.allowAt(key, t));
        assertFalse(rl.allowAt(key, t));

        // After 1 second
        assertTrue(rl.allowAt(key, t + 1000));
        assertFalse(rl.allowAt(key, t + 1000));
    }

    @Test
    void largeCapacity() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(1000, 100.0);
        String key = "user";
        long t = 1000;

        // Should allow full burst
        for (int i = 0; i < 1000; i++) {
            assertTrue(rl.allowAt(key, t));
        }
        assertFalse(rl.allowAt(key, t));
    }

    @Test
    void keysWithSpecialCharacters() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(5, 1.0);

        assertTrue(rl.allowAt("user:123", 1000));
        assertTrue(rl.allowAt("user/api/v1", 1000));
        assertTrue(rl.allowAt("user@domain.com", 1000));
        assertTrue(rl.allowAt("user-key_with.special", 1000));
        assertTrue(rl.allowAt("user中文", 1000));
    }

    @Test
    void newKeyStartsFull() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(10, 1.0);
        long t = 1000;

        // New key should start with full bucket
        for (int i = 0; i < 10; i++) {
            assertTrue(rl.allowAt("newKey" + i, t));
        }
    }

    @Test
    void sameKeyAfterDepletion() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(3, 1.0);
        String key = "user";
        long t = 1000;

        // Deplete bucket
        for (int i = 0; i < 3; i++) {
            assertTrue(rl.allowAt(key, t));
        }
        assertFalse(rl.allowAt(key, t));

        // Wait for refill
        assertTrue(rl.allowAt(key, t + 1000));
        assertFalse(rl.allowAt(key, t + 1000));

        // Wait more
        assertTrue(rl.allowAt(key, t + 2000));
        assertTrue(rl.allowAt(key, t + 3000));
    }

    @Test
    void rateOfOnePerSecond() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(1, 1.0);
        String key = "user";
        long t = 0;

        assertTrue(rl.allowAt(key, t));
        assertFalse(rl.allowAt(key, t));

        for (int i = 1; i <= 10; i++) {
            assertTrue(rl.allowAt(key, t + i * 1000));
            assertFalse(rl.allowAt(key, t + i * 1000));
        }
    }

    @Test
    void bucketStatePersistsAcrossCalls() {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(5, 2.0); // 2 tokens/s
        String key = "user";
        long t = 0;

        // Use 3 tokens
        for (int i = 0; i < 3; i++) {
            assertTrue(rl.allowAt(key, t));
        }
        // Remaining tokens: 5 - 3 = 2

        // After 1 second, should have 2 new tokens added to remaining 2 = 4 total
        assertTrue(rl.allowAt(key, t + 1000)); // 1st token, remaining: 3
        assertTrue(rl.allowAt(key, t + 1000)); // 2nd token, remaining: 2
        assertTrue(rl.allowAt(key, t + 1000)); // 3rd token, remaining: 1
        assertTrue(rl.allowAt(key, t + 1000)); // 4th token, remaining: 0
        assertFalse(rl.allowAt(key, t + 1000)); // No tokens left

        // After another second, should have 2 more tokens (total 2)
        assertTrue(rl.allowAt(key, t + 2000)); // 1st token, remaining: 1
        assertTrue(rl.allowAt(key, t + 2000)); // 2nd token, remaining: 0
        assertFalse(rl.allowAt(key, t + 2000)); // No tokens left
    }

    @Test
    void concurrentAccessToSameKey() throws InterruptedException {
        InMemoryTokenBucketRateLimiter rl = new InMemoryTokenBucketRateLimiter(100, 1000.0);
        String key = "concurrent";
        long t = System.currentTimeMillis();

        // Multiple threads accessing same key should be safe
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    rl.allowAt(key, t);
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        // Should not throw any exceptions
    }
}

