package io.github.cuihairu.redis.streaming.examples.state;

import io.github.cuihairu.redis.streaming.state.redis.RedisStateBackend;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives {@link StateExample}'s demo sections against a run-unique key prefix and pins
 * their returned summaries. The example hard-requires Redis, so everything here is
 * reachability-gated: it runs against the configured Redis when one is present and
 * skips cleanly otherwise.
 */
class StateExampleTest {

    private static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
    private static final String UUID_HEX = java.util.UUID.randomUUID().toString().substring(0, 8);

    @Test
    void mainRunsEndToEndAgainstRedis() {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping state example");
        assertDoesNotThrow(() -> StateExample.main(new String[0]));
    }

    @Test
    void demoSectionsRoundTripThroughRedisState() throws Exception {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping state example");

        RedissonClient redisson = redisson();
        String prefix = "examples:state:test:" + UUID_HEX + ":";
        try {
            RedisStateBackend backend = new RedisStateBackend(redisson, prefix);

            assertEquals(5, StateExample.demonstrateValueState(backend),
                    "5 visits must be accumulated before the counter is cleared");

            assertEquals(Map.of("theme", "dark", "language", "en", "timezone", "UTC"),
                    StateExample.demonstrateMapState(backend));

            assertEquals(4, StateExample.demonstrateListState(backend));

            assertEquals(4, StateExample.demonstrateSetState(backend),
                    "6 adds with 2 duplicates must yield 4 unique visitors");

            assertEquals(Map.of("hello", 2L, "world", 2L, "streaming", 2L, "of", 1L),
                    StateExample.demonstrateStatefulWordCount(backend));
        } finally {
            redisson.getKeys().deleteByPattern(prefix + "*");
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
