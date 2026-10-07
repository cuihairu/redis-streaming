package io.github.cuihairu.redis.streaming.examples.streaming;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ComprehensiveStreamingExample} end to end on run-unique topics and
 * service names (the prefix constructor was added for this — example-side change only)
 * and pins the pipeline outcome. Reachability-gated like the other example tests.
 */
class ComprehensiveStreamingExampleTest {

    private static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
    private static final String UUID_HEX = java.util.UUID.randomUUID().toString().substring(0, 8);

    @Test
    void pipelineProcessesAndSinksAllTenEvents() throws Exception {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping streaming example");

        RedissonClient redisson = redisson();
        try {
            ComprehensiveStreamingExample.Summary summary =
                    new ComprehensiveStreamingExample("examples:streaming:test:" + UUID_HEX + ":").runExample();

            assertEquals(10, summary.processed(),
                    "the processor group must transform all 10 raw events");
            assertEquals(10, summary.sinked(),
                    "the sink group must store all 10 processed events");
            assertTrue(summary.discoveredProducers() >= 1,
                    "the registered producer service must be discoverable");
            assertTrue(summary.discoveredProcessors() >= 1,
                    "the registered processor service must be discoverable");
        } finally {
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
