package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for the analytics helpers (PV/UV/quantile/top-k) against real Redis.
 */
@Tag("integration")
class AnalyticsIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void pvCounterCountsAndAggregates() {
        RedissonClient client = createClient();
        String prefix = "ana-pv-" + UUID.randomUUID().toString().substring(0, 8);
        PVCounter pv = new PVCounter(client, prefix, Duration.ofMinutes(5));
        try {
            Instant now = Instant.now();
            assertEquals(1L, pv.recordPageView("/home", now));
            assertEquals(2L, pv.recordPageView("/home", now));
            pv.recordPageView("/about", now);
            assertEquals(2L, pv.getPageViewCount("/home"));
            assertTrue(pv.getPageViewCount("/home", now.minusSeconds(60), now.plusSeconds(60)) >= 1);

            PVCounter.PVStatistics stats = pv.getStatistics();
            assertNotNull(stats);

            pv.resetPageViewCount("/home");
            assertEquals(0L, pv.getPageViewCount("/home"));
        } finally {
            pv.close();
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void uvCounterDeduplicatesVisitors() {
        RedissonClient client = createClient();
        String prefix = "ana-uv-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            UVCounter uv = new UVCounter(client, prefix, Duration.ofMinutes(10), Duration.ofSeconds(30));
            Instant now = Instant.now();
            assertTrue(uv.add("/home", "visitor-1", now));
            assertTrue(uv.add("/home", "visitor-2", now));
            assertTrue(uv.count("/home") >= 1);
            assertTrue(uv.count("/home", now) >= 1);
            assertTrue(uv.count("/home", now.minusSeconds(60), now.plusSeconds(60)) >= 1);
            uv.reset("/home");
            uv.close();
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void quantileAnalyzerServesPercentiles() {
        RedissonClient client = createClient();
        String prefix = "ana-q-" + UUID.randomUUID().toString().substring(0, 8);
        QuantileAnalyzer q = new QuantileAnalyzer(client, prefix, Duration.ofMinutes(5));
        try {
            Instant now = Instant.now();
            for (int i = 1; i <= 100; i++) {
                q.record("latency", i);
            }
            q.record("latency", 50, now);
            Double p50 = q.p50("latency");
            Double p95 = q.p95("latency");
            Double p99 = q.p99("latency");
            assertNotNull(p50);
            assertNotNull(p95);
            assertNotNull(p99);
            assertTrue(p50 <= p95 && p95 <= p99, p50 + " <= " + p95 + " <= " + p99);
            assertNotNull(q.quantile("latency", 0.9));
            assertNull(q.quantile("missing-metric", 0.5));
        } finally {
            q.close();
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void topKRanksRemovesAndResets() {
        RedissonClient client = createClient();
        String prefix = "ana-topk-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            TopKAnalyzer top = new TopKAnalyzer(client, prefix, 2, Duration.ofMinutes(5));
            top.recordItem("products", "a");
            top.recordItem("products", "b");
            top.recordItem("products", "b", 3.0);
            top.recordItem("products", "c");

            List<TopKAnalyzer.TopKItem> top2 = top.getTopK("products");
            assertEquals(2, top2.size());
            assertEquals("b", top2.get(0).getItem());
            assertFalse(top.getTopKWithRanks("products").isEmpty());
            assertTrue(top.getScore("products", "b") >= 4.0);
            assertTrue(top.getRank("products", "a") >= 0);

            top.removeItem("products", "a");
            top.reset("products");
            assertTrue(top.getTopK("products").isEmpty());
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }
}
