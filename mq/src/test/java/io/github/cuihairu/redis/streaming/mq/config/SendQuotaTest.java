package io.github.cuihairu.redis.streaming.mq.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SendQuotaTest {

    @Test
    void nullQuotaAlwaysAllows() {
        assertTrue(SendQuota.tryAcquire(null, "acme", "t"));
    }

    @Test
    void allowDecisionIsPassedThrough() {
        SendQuota quota = (tenant, topic) -> "acme".equals(tenant) && "t".equals(topic);

        assertTrue(SendQuota.tryAcquire(quota, "acme", "t"));
        assertFalse(SendQuota.tryAcquire(quota, "beta", "t"));
        assertFalse(SendQuota.tryAcquire(quota, "acme", "other"));
    }

    @Test
    void rateLimitedExceptionCarriesTenantAndTopic() {
        SendRateLimitedException ex = new SendRateLimitedException("acme", "orders");

        assertTrue(ex instanceof RuntimeException);
        assertTrue(ex.getMessage().contains("acme"));
        assertTrue(ex.getMessage().contains("orders"));
    }
}
