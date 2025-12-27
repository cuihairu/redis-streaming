package io.github.cuihairu.redis.streaming.examples.aggregation;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Random;

import io.github.cuihairu.redis.streaming.aggregation.SlidingWindow;
import io.github.cuihairu.redis.streaming.aggregation.TimeWindow;
import io.github.cuihairu.redis.streaming.aggregation.TumblingWindow;
import io.github.cuihairu.redis.streaming.aggregation.WindowAggregator;
import io.github.cuihairu.redis.streaming.aggregation.analytics.PVCounter;
import io.github.cuihairu.redis.streaming.aggregation.analytics.TopKAnalyzer;
import io.github.cuihairu.redis.streaming.aggregation.functions.AverageFunction;
import io.github.cuihairu.redis.streaming.aggregation.functions.CountFunction;
import io.github.cuihairu.redis.streaming.aggregation.functions.MaxFunction;
import io.github.cuihairu.redis.streaming.aggregation.functions.MinFunction;
import io.github.cuihairu.redis.streaming.aggregation.functions.SumFunction;

/**
 * Demonstrates basic windowed aggregation and analytics utilities.
 *
 * Requires Redis running (default `redis://127.0.0.1:6379`, override via `REDIS_URL`).
 */
@Slf4j
public class StreamAggregationExample {

    private final Random random = new Random();

    public static void main(String[] args) throws Exception {
        new StreamAggregationExample().run();
    }

    public void run() {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");

        Config config = new Config();
        config.useSingleServer()
                .setAddress(redisUrl)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10);

        RedissonClient redissonClient = Redisson.create(config);

        try {
            WindowAggregator aggregator = new WindowAggregator(redissonClient, "examples:aggregation");
            registerFunctions(aggregator);

            TimeWindow sliding = SlidingWindow.ofSeconds(60, 10);
            TimeWindow tumbling = TumblingWindow.ofSeconds(30);

            PVCounter pvCounter = aggregator.createPVCounter(Duration.ofMinutes(1));
            TopKAnalyzer topKAnalyzer = aggregator.createTopKAnalyzer(5, Duration.ofMinutes(1));

            log.info("Generating events for ~25s (sliding={} tumbling={} redisUrl={})", sliding.getSize(), tumbling.getSize(), redisUrl);

            long endAtMs = System.currentTimeMillis() + 25_000;
            int i = 0;
            while (System.currentTimeMillis() < endAtMs) {
                i++;
                Instant ts = Instant.now();

                String page = randomPage();
                double responseTimeMs = 50 + random.nextDouble() * 400;
                double amount = 10 + random.nextDouble() * 200;

                pvCounter.recordPageView(page, ts);
                topKAnalyzer.recordItem("pages", page);

                aggregator.addValue(sliding, "page_view_events", ts.toEpochMilli() + "-" + i, ts);
                aggregator.addValue(sliding, "response_time_ms", responseTimeMs, ts);
                aggregator.addValue(tumbling, "transaction_amount", amount, ts);

                if (i % 10 == 0) {
                    printSnapshot(aggregator, sliding, tumbling, pvCounter, topKAnalyzer, ts);
                }

                Thread.sleep(200);
            }

            printSnapshot(aggregator, sliding, tumbling, pvCounter, topKAnalyzer, Instant.now());
            pvCounter.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            redissonClient.shutdown();
        }
    }

    private void registerFunctions(WindowAggregator aggregator) {
        aggregator.registerFunction("COUNT", CountFunction.getInstance());
        aggregator.registerFunction("SUM", SumFunction.getInstance());
        aggregator.registerFunction("AVERAGE", AverageFunction.getInstance());
        aggregator.registerFunction("MIN", MinFunction.<Double>create());
        aggregator.registerFunction("MAX", MaxFunction.<Double>create());
    }

    private void printSnapshot(
            WindowAggregator aggregator,
            TimeWindow sliding,
            TimeWindow tumbling,
            PVCounter pvCounter,
            TopKAnalyzer topKAnalyzer,
            Instant ts
    ) {
        Long pv = aggregator.getAggregatedResult(sliding, "page_view_events", "COUNT", ts);
        var avg = aggregator.getAggregatedResult(sliding, "response_time_ms", "AVERAGE", ts);
        Double min = aggregator.getAggregatedResult(sliding, "response_time_ms", "MIN", ts);
        Double max = aggregator.getAggregatedResult(sliding, "response_time_ms", "MAX", ts);
        var revenue = aggregator.getAggregatedResult(tumbling, "transaction_amount", "SUM", ts);

        log.info("snapshot ts={} pv(last60s)={} revenue(last30s)={} avgRespMs={} min/maxRespMs={}/{}",
                ts, pv, revenue, avg, min, max);

        String[] pages = {"/home", "/products", "/cart", "/checkout", "/profile"};
        Map<String, Long> pvByPage = Map.of(
                pages[0], pvCounter.getPageViewCount(pages[0]),
                pages[1], pvCounter.getPageViewCount(pages[1]),
                pages[2], pvCounter.getPageViewCount(pages[2]),
                pages[3], pvCounter.getPageViewCount(pages[3]),
                pages[4], pvCounter.getPageViewCount(pages[4])
        );

        log.info("pv(1m) by page: {}", pvByPage);
        log.info("top pages: {}", topKAnalyzer.getTopK("pages"));
    }

    private String randomPage() {
        String[] pages = {"/home", "/products", "/cart", "/checkout", "/profile"};
        return pages[random.nextInt(pages.length)];
    }
}
