package io.github.cuihairu.redis.streaming.examples.aggregation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link StreamAggregationExample} against a run-unique namespace and pins the
 * final snapshot it reports. The example hard-requires Redis, so everything here is
 * reachability-gated: it runs against the configured Redis when one is present and
 * skips cleanly otherwise.
 */
class StreamAggregationExampleTest {

    private static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
    private static final String UUID_HEX = java.util.UUID.randomUUID().toString().substring(0, 8);

    private static final Set<String> PAGES = Set.of("/home", "/products", "/cart", "/checkout", "/profile");

    @Test
    void aggregationRunProducesObservableSnapshot() throws Exception {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping aggregation example");

        RedissonClient redisson = redisson();
        String namespace = "examples:aggregation:test:" + UUID_HEX;
        try {
            StreamAggregationExample.Snapshot snapshot =
                    new StreamAggregationExample().run(4_000, 50, namespace);

            assertTrue(snapshot.windowPageViews() >= 1,
                    "events were generated for 4s — the windowed PV must count every generated event");
            assertTrue(snapshot.windowUniquePages() >= 1 && snapshot.windowUniquePages() <= PAGES.size(),
                    "windowed UV is bounded by the demo page set");
            assertTrue(PAGES.contains(snapshot.windowTopPage()),
                    "the window TopK winner must be drawn from the demo page set");
            assertTrue(snapshot.windowRevenue() > 0,
                    "transaction amounts are >= 10 — the windowed SUM must be positive");
            assertTrue(snapshot.windowP90ResponseMs() >= 50,
                    "response times are >= 50ms — the window p90 must be at least that");

            assertEquals(PAGES, snapshot.pageViewsByPage().keySet(),
                    "every demo page must appear in the PV counter");
            long pvSum = snapshot.pageViewsByPage().values().stream().mapToLong(Long::longValue).sum();
            assertTrue(pvSum >= 1, "PVCounter must have recorded the generated page views");

            assertNotNull(snapshot.topPages());
            assertFalse(snapshot.topPages().isEmpty(), "TopK must have entries after the run");
            assertTrue(snapshot.topPages().stream().allMatch(PAGES::contains),
                    "top pages must be drawn from the demo page set");
        } finally {
            redisson.getKeys().deleteByPattern(namespace + "*");
            redisson.shutdown();
        }
    }

    private static RedissonClient redisson() {
        Config config = new Config();
        config.useSingleServer().setAddress(REDIS_URL);
        return Redisson.create(config);
    }

    private static boolean reachable() {
        Matcher m = Pattern.compile("://([^/:]+):(\\d+)").matcher(REDIS_URL);
        String host = "127.0.0.1";
        int port = 6379;
        if (m.find()) {
            host = m.group(1);
            port = Integer.parseInt(m.group(2));
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
