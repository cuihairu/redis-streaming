package io.github.cuihairu.redis.streaming.aggregation;

import io.github.cuihairu.redis.streaming.aggregation.analytics.PVCounter;
import io.github.cuihairu.redis.streaming.aggregation.analytics.TopKAnalyzer;
import io.github.cuihairu.redis.streaming.aggregation.functions.CountFunction;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Integration example demonstrating the aggregation functionality
 */
@Slf4j
@Tag("integration")
public class AggregationIntegrationExample {

    @Test
    public void testAggregationIntegration() throws Exception {
        // Create Redis configuration
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://localhost:6379")
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10);

        RedissonClient redissonClient = Redisson.create(config);

        try {
            runExample(redissonClient);
        } finally {
            redissonClient.shutdown();
        }
    }

    private static void runExample(RedissonClient redissonClient) throws Exception {
        log.info("=== Aggregation Module Integration Example ===");

        // Test time windows
        testTimeWindows();

        // Test window aggregator
        testWindowAggregator(redissonClient);

        // Test PV counter
        testPVCounter(redissonClient);

        // Test Top-K analyzer
        testTopKAnalyzer(redissonClient);
    }

    private static void testTimeWindows() {
        log.info("=== Testing Time Windows ===");

        // Test tumbling window
        TumblingWindow tumblingWindow = TumblingWindow.ofMinutes(5);
        Instant now = Instant.now();
        Instant windowStart = tumblingWindow.getWindowStart(now);
        Instant windowEnd = tumblingWindow.getWindowEnd(now);

        log.info("Tumbling Window (5 min): {} - {}", windowStart, windowEnd);
        log.info("Contains current time: {}", tumblingWindow.contains(now, windowStart));

        // Test sliding window
        SlidingWindow slidingWindow = SlidingWindow.ofMinutes(10, 2);
        List<Instant> overlappingWindows = slidingWindow.getOverlappingWindows(now);

        log.info("Sliding Window (10 min / 2 min slide): {} overlapping windows", overlappingWindows.size());
        overlappingWindows.forEach(ws -> log.info("  Window: {} - {}", ws, ws.plus(slidingWindow.getSize())));
    }

    private static void testWindowAggregator(RedissonClient redissonClient) throws Exception {
        log.info("=== Testing Window Aggregator ===");

        WindowAggregator aggregator = new WindowAggregator(redissonClient, "example");

        // Register aggregation functions
        aggregator.registerFunction("COUNT", CountFunction.getInstance());

        TumblingWindow window = TumblingWindow.ofSeconds(30);
        String key = "page_views";

        // Add some values
        Instant now = Instant.now();
        for (int i = 0; i < 10; i++) {
            aggregator.addValue(window, key, "view_" + i, now.plusSeconds(i));
        }

        // Get aggregated result
        Long count = aggregator.getAggregatedResult(window, key, "COUNT", now.plusSeconds(15));
        log.info("Aggregated count: {}", count);

        // Get window statistics
        WindowAggregator.WindowStatistics stats = aggregator.getWindowStatistics(window, key, now.plusSeconds(15));
        log.info("Window stats: {} values from {} to {}",
                stats.getValueCount(), stats.getWindowStart(), stats.getWindowEnd());
    }

    private static void testPVCounter(RedissonClient redissonClient) throws Exception {
        log.info("=== Testing PV Counter ===");

        PVCounter pvCounter = new PVCounter(redissonClient, "example", Duration.ofMinutes(10));

        try {
            // Simulate page views
            String[] pages = {"home", "about", "products", "contact"};

            for (int i = 0; i < 20; i++) {
                String page = pages[ThreadLocalRandom.current().nextInt(pages.length)];
                long count = pvCounter.recordPageView(page);
                log.info("Page '{}' views: {}", page, count);

                Thread.sleep(100); // Small delay to simulate real traffic
            }

            // Get current counts
            for (String page : pages) {
                long count = pvCounter.getPageViewCount(page);
                log.info("Final count for '{}': {}", page, count);
            }

            // Test time range query
            Instant start = Instant.now().minus(Duration.ofMinutes(5));
            Instant end = Instant.now();
            long recentViews = pvCounter.getPageViewCount("home", start, end);
            log.info("Recent views for 'home' (last 5 min): {}", recentViews);

        } finally {
            pvCounter.close();
        }
    }

    private static void testTopKAnalyzer(RedissonClient redissonClient) throws Exception {
        log.info("=== Testing Top-K Analyzer ===");

        TopKAnalyzer topK = new TopKAnalyzer(redissonClient, "example", 5, Duration.ofMinutes(10));

        // Simulate user activity
        String[] users = {"alice", "bob", "charlie", "diana", "eve", "frank", "grace"};
        String category = "active_users";

        for (int i = 0; i < 50; i++) {
            String user = users[ThreadLocalRandom.current().nextInt(users.length)];
            double weight = ThreadLocalRandom.current().nextDouble(1.0, 5.0);
            topK.recordItem(category, user, weight);
        }

        // Get top K results
        List<TopKAnalyzer.TopKItem> topItems = topK.getTopK(category);
        log.info("Top {} users:", topItems.size());
        topItems.forEach(item ->
                log.info("  {}: {} points", item.getItem(), item.getScore()));

        // Get top K with ranks
        List<TopKAnalyzer.TopKItemWithRank> topWithRanks = topK.getTopKWithRanks(category);
        log.info("Top users with ranks:");
        topWithRanks.forEach(item ->
                log.info("  Rank {}: {} ({} points)",
                        item.getRank(), item.getItem(), item.getScore()));

        // Check specific user rank
        int aliceRank = topK.getRank(category, "alice");
        double aliceScore = topK.getScore(category, "alice");
        log.info("Alice: Rank {} with {} points",
                aliceRank == -1 ? "Not in top K" : aliceRank, aliceScore);
    }
}