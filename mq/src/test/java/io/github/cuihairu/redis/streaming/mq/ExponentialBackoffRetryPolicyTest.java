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
}

