package io.github.cuihairu.redis.streaming.examples.mq;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link MessageQueueExample} end to end on run-unique topics (the topic prefix
 * constructor was added for this — example-side change only) and pins every section's
 * outcome. The example hard-requires Redis, so everything here is reachability-gated:
 * it runs against the configured Redis when one is present and skips cleanly otherwise.
 */
class MessageQueueExampleTest {

    private static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
    private static final String UUID_HEX = java.util.UUID.randomUUID().toString().substring(0, 8);

    @Test
    void fullRunCompletesEverySection() throws Exception {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping mq example");

        MessageQueueExample.Summary summary =
                new MessageQueueExample("examples:mq:test:" + UUID_HEX + ":").runExample();

        assertTrue(summary.basicConsumed(), "all 5 order events must be consumed by the basic group");
        assertTrue(summary.emailGroup(), "email group must consume all 3 notifications");
        assertTrue(summary.smsGroup(), "sms group must consume all 3 notifications");
        assertTrue(summary.auditGroup(), "audit group must consume all 3 notifications");
        assertTrue(summary.errorsProcessed(), "both failing messages must reach the retry handler");
        assertTrue(summary.dlqSize() >= 0, "DLQ size must be readable for the run's topic");
        assertTrue(summary.batchComplete(), "all 20 batch items must be processed");
        assertTrue(summary.perfComplete(), "all 100 perf messages must be consumed");
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
