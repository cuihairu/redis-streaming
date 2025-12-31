package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.retry.ExponentialBackoffRetryPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExponentialBackoffRetryPolicyTest {

    @Test
    void backoffGrowsAndCaps() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(10, 100, 800);
        assertEquals(100, p.nextBackoffMs(1)); // 100 * 2^(1-1)
        assertEquals(200, p.nextBackoffMs(2));
        assertEquals(400, p.nextBackoffMs(3));
        assertEquals(800, p.nextBackoffMs(4));
        assertEquals(800, p.nextBackoffMs(5)); // capped
        assertEquals(800, p.nextBackoffMs(10));
        assertEquals(10, p.getMaxAttempts());
    }

    @Test
    void constructorWithZeroBaseMs() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 0, 100);
        assertEquals(0, p.nextBackoffMs(1));
        assertEquals(0, p.nextBackoffMs(2));
        assertEquals(5, p.getMaxAttempts());
    }

    @Test
    void constructorWithZeroMaxAttempts() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(0, 100, 800);
        assertEquals(1, p.getMaxAttempts()); // min is 1
    }

    @Test
    void constructorWithNegativeMaxAttempts() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(-5, 100, 800);
        assertEquals(1, p.getMaxAttempts()); // min is 1
    }

    @Test
    void constructorWithNegativeBaseMs() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, -100, 800);
        assertEquals(0, p.nextBackoffMs(1)); // min is 0
    }

    @Test
    void constructorWithMaxBackoffLessThanBase() {
        // When maxBackoff < base, it should be adjusted to base
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 500, 100);
        assertEquals(500, p.nextBackoffMs(1));
        assertEquals(500, p.nextBackoffMs(2)); // capped at base since maxBackoff adjusted
    }

    @Test
    void nextBackoffMsWithFirstAttempt() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 200, 1600);
        assertEquals(200, p.nextBackoffMs(1));
    }

    @Test
    void nextBackoffMsWithSecondAttempt() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 100, 800);
        assertEquals(200, p.nextBackoffMs(2));
    }

    @Test
    void nextBackoffMsWithThirdAttempt() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 50, 400);
        assertEquals(200, p.nextBackoffMs(3)); // 50 * 2^2 = 200
    }

    @Test
    void nextBackoffMsWithLargeAttemptNumber() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(10, 10, 1000);
        assertEquals(10, p.nextBackoffMs(1));      // 10 * 2^0 = 10
        assertEquals(20, p.nextBackoffMs(2));      // 10 * 2^1 = 20
        assertEquals(40, p.nextBackoffMs(3));      // 10 * 2^2 = 40
        assertEquals(80, p.nextBackoffMs(4));      // 10 * 2^3 = 80
        assertEquals(160, p.nextBackoffMs(5));     // 10 * 2^4 = 160
        assertEquals(320, p.nextBackoffMs(6));     // 10 * 2^5 = 320
        assertEquals(640, p.nextBackoffMs(7));     // 10 * 2^6 = 640
        assertEquals(1000, p.nextBackoffMs(8));    // capped
        assertEquals(1000, p.nextBackoffMs(100));  // capped
    }

    @Test
    void nextBackoffMsWithZeroAttemptNumber() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 100, 800);
        // attempt 0 is treated as attempt 1 due to max(0, attempt - 1)
        assertEquals(100, p.nextBackoffMs(0));
    }

    @Test
    void nextBackoffMsWithNegativeAttemptNumber() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 100, 800);
        // negative attempt is treated as attempt 1
        assertEquals(100, p.nextBackoffMs(-1));
    }

    @Test
    void getMaxAttemptsReturnsCorrectValue() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(7, 100, 800);
        assertEquals(7, p.getMaxAttempts());
    }

    @Test
    void getMaxAttemptsWithLargeValue() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(1000, 100, 800);
        assertEquals(1000, p.getMaxAttempts());
    }

    @Test
    void exponentialGrowthPattern() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(10, 1, 10000);
        // Verify exponential growth: base * 2^(attempt-1)
        assertEquals(1, p.nextBackoffMs(1));    // 1 * 2^0 = 1
        assertEquals(2, p.nextBackoffMs(2));    // 1 * 2^1 = 2
        assertEquals(4, p.nextBackoffMs(3));    // 1 * 2^2 = 4
        assertEquals(8, p.nextBackoffMs(4));    // 1 * 2^3 = 8
        assertEquals(16, p.nextBackoffMs(5));   // 1 * 2^4 = 16
        assertEquals(32, p.nextBackoffMs(6));   // 1 * 2^5 = 32
        assertEquals(64, p.nextBackoffMs(7));   // 1 * 2^6 = 64
        assertEquals(128, p.nextBackoffMs(8));  // 1 * 2^7 = 128
    }

    @Test
    void capIsRespectedExactlyAtBoundary() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(10, 100, 400);
        assertEquals(100, p.nextBackoffMs(1)); // 100 * 2^0 = 100
        assertEquals(200, p.nextBackoffMs(2)); // 100 * 2^1 = 200
        assertEquals(400, p.nextBackoffMs(3)); // 100 * 2^2 = 400 (exactly at cap)
        assertEquals(400, p.nextBackoffMs(4)); // would be 800, capped at 400
    }

    @Test
    void noCapWhenMaxBackoffIsLarge() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(10, 1, Long.MAX_VALUE);
        assertEquals(1, p.nextBackoffMs(1));
        assertEquals(2, p.nextBackoffMs(2));
        assertEquals(4, p.nextBackoffMs(3));
        assertEquals(8, p.nextBackoffMs(4));
        assertEquals(16, p.nextBackoffMs(5));
        // No capping with very large maxBackoff
        assertEquals(256, p.nextBackoffMs(9)); // 1 * 2^8 = 256
    }

    @Test
    void singleAttemptPolicy() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(1, 100, 800);
        assertEquals(1, p.getMaxAttempts());
        assertEquals(100, p.nextBackoffMs(1));
    }

    @Test
    void twoAttemptPolicy() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(2, 50, 200);
        assertEquals(2, p.getMaxAttempts());
        assertEquals(50, p.nextBackoffMs(1));
        assertEquals(100, p.nextBackoffMs(2)); // 50 * 2
    }

    @Test
    void consistentBackoffForSameAttempt() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 100, 800);
        // Calling nextBackoffMs multiple times with same attempt should return same value
        for (int i = 0; i < 5; i++) {
            assertEquals(200, p.nextBackoffMs(2));
            assertEquals(400, p.nextBackoffMs(3));
        }
    }

    @Test
    void largeBaseValue() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 5000, 40000);
        assertEquals(5000, p.nextBackoffMs(1));
        assertEquals(10000, p.nextBackoffMs(2));
        assertEquals(20000, p.nextBackoffMs(3));
        assertEquals(40000, p.nextBackoffMs(4)); // capped
        assertEquals(40000, p.nextBackoffMs(5)); // capped
    }

    @Test
    void oddBaseValue() {
        ExponentialBackoffRetryPolicy p = new ExponentialBackoffRetryPolicy(5, 123, 2000);
        assertEquals(123, p.nextBackoffMs(1));
        assertEquals(246, p.nextBackoffMs(2));
        assertEquals(492, p.nextBackoffMs(3));
        assertEquals(984, p.nextBackoffMs(4));
        assertEquals(1968, p.nextBackoffMs(5));
    }
}
