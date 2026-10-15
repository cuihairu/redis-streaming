package io.github.cuihairu.redis.streaming.starter.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tenant tag at the export boundary: off by default so existing dashboards keep
 * their exact series set, and on demand through an explicit flag.
 */
public class MqMicrometerCollectorTenantTagTest {

    private static final String TENANT = "acme";
    private static final String TOPIC = "t";
    private static final double EPS = 0.0001;

    @Test
    public void tenantTagOffByDefault() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MqMicrometerCollector c = new MqMicrometerCollector(registry);

        // tenant-aware SPI calls still land on the legacy, tenant-free series
        c.incConsumed(TENANT, TOPIC, 0);
        c.incAcked(TENANT, TOPIC, 0);
        c.incRetried(TENANT, TOPIC, 0);
        c.incDeadLetter(TENANT, TOPIC, 0);
        c.incProduced(TENANT, TOPIC, 0);
        c.recordHandleLatency(TENANT, TOPIC, 0, 5);
        c.incRateLimited(TENANT, TOPIC);

        for (String name : new String[]{
                "redis_streaming_mq_consumed_total", "redis_streaming_mq_acked_total",
                "redis_streaming_mq_retried_total", "redis_streaming_mq_dead_total",
                "redis_streaming_mq_produced_total"}) {
            assertEquals(1.0, registry.get(name).tag("topic", TOPIC).tag("partition", "0").counter().count(), EPS);
        }
        assertTrue(registry.get("redis_streaming_mq_handle_latency_ms")
                .tag("topic", TOPIC).tag("partition", "0").timer().count() >= 1);
        // rate-limited has no legacy variant, so it launches with its native tenant tag
        assertEquals(1.0, registry.get("redis_streaming_mq_rate_limited_total")
                .tag("tenant", TENANT).tag("topic", TOPIC).counter().count(), EPS);
    }

    @Test
    public void tenantTagOnCarriesTheTenantDimension() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MqMicrometerCollector c = new MqMicrometerCollector(registry, true);

        c.incProduced(TENANT, TOPIC, 0);
        c.incConsumed(TENANT, TOPIC, 0);
        c.incAcked(TENANT, TOPIC, 0);
        c.incRetried(TENANT, TOPIC, 0);
        c.incDeadLetter(TENANT, TOPIC, 0);
        c.recordHandleLatency(TENANT, TOPIC, 0, 5);
        c.incRateLimited(TENANT, TOPIC);

        for (String name : new String[]{
                "redis_streaming_mq_produced_total", "redis_streaming_mq_consumed_total",
                "redis_streaming_mq_acked_total", "redis_streaming_mq_retried_total",
                "redis_streaming_mq_dead_total"}) {
            assertEquals(1.0, registry.get(name).tag("tenant", TENANT)
                    .tag("topic", TOPIC).tag("partition", "0").counter().count(), EPS);
        }
        assertTrue(registry.get("redis_streaming_mq_handle_latency_ms")
                .tag("tenant", TENANT).tag("topic", TOPIC).tag("partition", "0").timer().count() >= 1);
        assertEquals(1.0, registry.get("redis_streaming_mq_rate_limited_total")
                .tag("tenant", TENANT).tag("topic", TOPIC).counter().count(), EPS);
    }

    @Test
    public void legacySeriesStayUntaggedWhenTenantTagOn() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MqMicrometerCollector c = new MqMicrometerCollector(registry, true);

        c.incConsumed(TENANT, TOPIC, 0);
        c.incConsumed(TOPIC, 0);

        assertEquals(1.0, registry.get("redis_streaming_mq_consumed_total")
                .tag("tenant", TENANT).tag("topic", TOPIC).tag("partition", "0").counter().count(), EPS);
        assertEquals(1.0, registry.get("redis_streaming_mq_consumed_total")
                .tag("topic", TOPIC).tag("partition", "0").counter().count(), EPS);
    }

    @Test
    public void cardinalityWarnsOncePastTheTenantTopicBudget() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MqMicrometerCollector c = new MqMicrometerCollector(registry, true, 2);

        assertFalse(c.cardinalityWarned());
        c.incConsumed("a", "t1", 0);
        c.incConsumed("b", "t2", 0);
        assertFalse(c.cardinalityWarned(), "threshold of 2 pairs must not warn at exactly 2");
        c.incConsumed("c", "t3", 0);
        assertTrue(c.cardinalityWarned(), "third pair must trip the warn");
        c.incConsumed("d", "t4", 0);
        assertTrue(c.cardinalityWarned(), "warn stays latched for the whole instance lifetime");
        assertEquals(1.0, registry.get("redis_streaming_mq_consumed_total")
                .tag("tenant", "d").tag("topic", "t4").tag("partition", "0").counter().count(), EPS);
    }
}
