package io.github.cuihairu.redis.streaming.mq.config;

/**
 * Production rate quota checked by the producer before every append
 * (docs/Multi-Tenancy-Design.md, quota item 1).
 *
 * <p>The MQ layer only defines the hook; implementations typically adapt a
 * {@code reliability.ratelimit.RateLimiter} (Redis token bucket / sliding window)
 * with the bucket key {@code tenant:topic}. Kept as an interface inside mq so the
 * MQ module stays free of reliability dependencies (see
 * docs/Dedup-Retry-DLQ-Boundary-Design.md).</p>
 *
 * <p>Rejection semantics: the send fails fast with {@link SendRateLimitedException}
 * and is counted by {@code MqMetrics.incRateLimited(tenant, topic)} — the producer
 * never blocks the caller thread.</p>
 */
@FunctionalInterface
public interface SendQuota {

    /**
     * @param tenant tenant namespace of the send (never null)
     * @param topic  target topic
     * @return true to allow the append; false to reject the send
     */
    boolean allow(String tenant, String topic);

    /**
     * Shared gate used by every physical write path: counts the rejection and reports the
     * decision. A null quota (disabled) always allows.
     *
     * @return true when the send may proceed
     */
    static boolean tryAcquire(SendQuota quota, String tenant, String topic) {
        if (quota == null) {
            return true;
        }
        boolean allowed = quota.allow(tenant, topic);
        if (!allowed) {
            io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics.get().incRateLimited(tenant, topic);
        }
        return allowed;
    }
}
