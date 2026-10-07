package io.github.cuihairu.redis.streaming.examples.registry;

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

/**
 * Drives {@link CustomPrefixExample} under a run-unique Redis key prefix (the
 * {@code run(keyPrefix)} variant was added for this — example-side change only) and
 * pins the discovered instance count. Reachability-gated like the other example tests.
 */
class CustomPrefixExampleTest {

    private static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
    private static final String UUID_HEX = java.util.UUID.randomUUID().toString().substring(0, 8);

    @Test
    void runUnderCustomPrefixRegistersAndDiscoversTheInstance() throws Exception {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping custom prefix example");

        RedissonClient redisson = redisson();
        String keyPrefix = "examples-custom-prefix-test-" + UUID_HEX;
        try {
            int discovered = CustomPrefixExample.run(keyPrefix);

            assertEquals(1, discovered,
                    "the single registered instance must be discoverable under the custom prefix");
        } finally {
            redisson.getKeys().deleteByPattern(keyPrefix + "*");
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
