package io.github.cuihairu.redis.streaming.examples.aggregation;

import io.github.cuihairu.redis.streaming.aggregation.analytics.PVCounter;
import io.github.cuihairu.redis.streaming.aggregation.analytics.TopKAnalyzer;
import io.github.cuihairu.redis.streaming.aggregation.incremental.PVAggregateFunction;
import io.github.cuihairu.redis.streaming.aggregation.incremental.QuantileAggregateFunction;
import io.github.cuihairu.redis.streaming.aggregation.incremental.TopKAggregateFunction;
import io.github.cuihairu.redis.streaming.aggregation.incremental.UVAggregateFunction;
import io.github.cuihairu.redis.streaming.api.stream.AggregateFunction;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.runtime.internal.InMemoryKeyedStream;
import io.github.cuihairu.redis.streaming.window.assigners.TumblingWindow;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Demonstrates the two complementary aggregation layers of this library:
 *
 * <ul>
 *   <li><b>Windowed pipelines</b>: the core incremental {@link AggregateFunction}
 *       implementations ({@code aggregation.incremental}) fed through a windowed stream —
 *       PV / UV / TopK / quantiles accumulate per window and fire one result per window.</li>
 *   <li><b>Redis-backed rolling analytics</b> ({@code aggregation.analytics}): {@link PVCounter}
 *       and {@link TopKAnalyzer} keep queryable, TTL-managed rolling windows in Redis across
 *       processes — no pipeline required.</li>
 * </ul>
 *
 * Requires Redis running (default `redis://127.0.0.1:6379`, override via `REDIS_URL`).
 */
@Slf4j
public class StreamAggregationExample {

    private final Random random = new Random();

    /** Observable outcome of one demo run. */
    record Snapshot(long windowPageViews, long windowUniquePages, String windowTopPage,
                    double windowRevenue, long windowP90ResponseMs,
                    Map<String, Long> pageViewsByPage, List<String> topPages) {
    }

    public static void main(String[] args) throws Exception {
        new StreamAggregationExample().run();
    }

    public void run() throws Exception {
        run(25_000, 200, "examples:aggregation");
    }

    /**
     * Bounded variant of the demo used by tests: generates events for {@code durationMs}
     * sleeping {@code sleepMs} between them, then returns the final snapshot.
     */
    Snapshot run(long durationMs, long sleepMs, String namespace) throws InterruptedException {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");

        Config config = new Config();
        config.useSingleServer()
                .setAddress(redisUrl)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10);

        RedissonClient redissonClient = Redisson.create(config);

        List<String> pages = new ArrayList<>();
        List<Double> responseTimes = new ArrayList<>();
        List<Double> amounts = new ArrayList<>();

        try {
            PVCounter pvCounter = new PVCounter(redissonClient, namespace, Duration.ofMinutes(1));
            TopKAnalyzer topKAnalyzer = new TopKAnalyzer(redissonClient, namespace, 5, Duration.ofMinutes(1));

            log.info("Generating events for ~{}ms (redisUrl={})", durationMs, redisUrl);

            long endAtMs = System.currentTimeMillis() + durationMs;
            int i = 0;
            while (System.currentTimeMillis() < endAtMs) {
                i++;
                Instant ts = Instant.now();

                String page = randomPage();
                double responseTimeMs = 50 + random.nextDouble() * 400;
                double amount = 10 + random.nextDouble() * 200;

                pages.add(page);
                responseTimes.add(responseTimeMs);
                amounts.add(amount);

                pvCounter.recordPageView(page, ts);
                topKAnalyzer.recordItem("pages", page);

                if (i % 10 == 0) {
                    log.info("generated {} events; pv(1m) by page: {}; top pages: {}",
                            i, pageViewsByPage(pvCounter), topKAnalyzer.getTopK("pages"));
                }

                Thread.sleep(sleepMs);
            }

            Snapshot snapshot = snapshotOf(pages, responseTimes, amounts, pvCounter, topKAnalyzer);
            log.info("windowed snapshot: pv={} uv={} top={} revenue={} p90RespMs={}",
                    snapshot.windowPageViews(), snapshot.windowUniquePages(), snapshot.windowTopPage(),
                    snapshot.windowRevenue(), snapshot.windowP90ResponseMs());
            log.info("pv(1m) by page: {}", snapshot.pageViewsByPage());
            log.info("top pages: {}", snapshot.topPages());
            pvCounter.close();
            return snapshot;
        } finally {
            redissonClient.shutdown();
        }
    }

    /**
     * Feeds the events generated above through the incremental {@code AggregateFunction}
     * implementations. Every pipeline sees one tumbling window (the in-memory source stamps
     * records with increasing timestamps), so each fires exactly one result.
     */
    private Snapshot snapshotOf(List<String> pages, List<Double> responseTimes, List<Double> amounts,
                                PVCounter pvCounter, TopKAnalyzer topKAnalyzer) {
        long pv = fireOneWindow(pages, new PVAggregateFunction<>());
        long uv = fireOneWindow(pages, new UVAggregateFunction<>());
        List<TopKAggregateFunction.TopKEntry<String>> top =
                fireOneWindow(pages, new TopKAggregateFunction<>(5));
        double revenue = fireOneWindow(amounts, new SumAggregateFunction());
        Double p90 = fireOneWindow(responseTimes, new QuantileAggregateFunction<>(0.9));

        return new Snapshot(
                pv,
                uv,
                top.isEmpty() ? null : top.get(0).item(),
                revenue,
                p90 == null ? 0L : Math.round(p90),
                pageViewsByPage(pvCounter),
                topKAnalyzer.getTopK("pages").stream()
                        .map(TopKAnalyzer.TopKItem::getItem)
                        .toList()
        );
    }

    /** Runs one finite event list through one tumbling window and returns the fired result. */
    private static <T, R> R fireOneWindow(List<T> events, AggregateFunction<T, R> fn) {
        List<R> results = new ArrayList<>();
        KeyedStream<String, T> keyed = new InMemoryKeyedStream<>(events::iterator, e -> "demo");
        keyed.window(TumblingWindow.ofMillis(60_000))
                .aggregate(fn)
                .addSink(results::add);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * A custom aggregate written against the core interface — the single aggregation
     * extension point since the batch API was removed. Accumulator must be serializable:
     * window state round-trips through JSON.
     */
    static class SumAggregateFunction implements AggregateFunction<Double, Double> {
        static class SumAcc implements Accumulator<Double> {
            double sum;

            double getSum() {
                return sum;
            }

            @SuppressWarnings("unused")
            void setSum(double sum) {
                this.sum = sum;
            }
        }

        @Override
        public Accumulator<Double> createAccumulator() {
            return new SumAcc();
        }

        @Override
        public Accumulator<Double> add(Double value, Accumulator<Double> acc) {
            ((SumAcc) acc).sum += value;
            return acc;
        }

        @Override
        public Double getResult(Accumulator<Double> acc) {
            return ((SumAcc) acc).sum;
        }

        @Override
        public Accumulator<Double> merge(Accumulator<Double> a, Accumulator<Double> b) {
            SumAcc merged = new SumAcc();
            merged.sum = ((SumAcc) a).sum + ((SumAcc) b).sum;
            return merged;
        }
    }

    private static Map<String, Long> pageViewsByPage(PVCounter pvCounter) {
        String[] pages = {"/home", "/products", "/cart", "/checkout", "/profile"};
        Map<String, Long> pvByPage = Map.of(
                pages[0], pvCounter.getPageViewCount(pages[0]),
                pages[1], pvCounter.getPageViewCount(pages[1]),
                pages[2], pvCounter.getPageViewCount(pages[2]),
                pages[3], pvCounter.getPageViewCount(pages[3]),
                pages[4], pvCounter.getPageViewCount(pages[4])
        );
        return pvByPage;
    }

    private String randomPage() {
        String[] pages = {"/home", "/products", "/cart", "/checkout", "/profile"};
        return pages[random.nextInt(pages.length)];
    }
}
