package io.github.cuihairu.redis.streaming.aggregation.analytics;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for B-18: {@code windowSize} used to be stored and never read — scores
 * accumulated forever and rank trimming permanently deleted low-ranked items, so "Top-K in
 * the last window" was actually "Top-K of all time". This test uses only the public
 * constructor so it compiles against the pre-fix implementation, where the recorded score
 * never decays.
 */
@Tag("integration")
class TopKAnalyzerWindowDecayIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void scoresExpireOnceTheirWindowHasPassed() throws Exception {
        RedissonClient client = createClient();
        String prefix = "ana-topkfix-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            TopKAnalyzer analyzer = new TopKAnalyzer(client, prefix, 3, Duration.ofMillis(500));

            analyzer.recordItem("products", "gone");
            analyzer.recordItem("products", "gone");
            analyzer.recordItem("products", "gone", 1.0);

            assertEquals(3.0, analyzer.getScore("products", "gone"), 1e-9,
                    "setup: the record must be visible inside the window");

            Thread.sleep(1200);

            assertEquals(0.0, analyzer.getScore("products", "gone"), 1e-9,
                    "old code ignored windowSize and kept the score forever");
            assertTrue(analyzer.getTopK("products").isEmpty(),
                    "old code still reported the expired item as top-K");
            assertEquals(-1, analyzer.getRank("products", "gone"),
                    "expired items must have no rank in the current window");
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }

    @Test
    void itemsInsideTheWindowStillAggregate() {
        RedissonClient client = createClient();
        String prefix = "ana-topkkeep-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            TopKAnalyzer analyzer = new TopKAnalyzer(client, prefix, 3, Duration.ofSeconds(5));

            analyzer.recordItem("products", "hot");
            analyzer.recordItem("products", "hot");
            analyzer.recordItem("products", "warm");

            List<TopKAnalyzer.TopKItem> top = analyzer.getTopK("products");
            assertEquals(2, top.size());
            assertEquals("hot", top.get(0).getItem());
            assertEquals(2.0, top.get(0).getScore(), 1e-9);
            assertEquals(1.0, top.get(1).getScore(), 1e-9);
        } finally {
            client.getKeys().deleteByPattern(prefix + "*");
            client.shutdown();
        }
    }
}
