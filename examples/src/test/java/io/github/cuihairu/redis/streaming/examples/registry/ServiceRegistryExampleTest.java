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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link ServiceRegistryExample} end to end on run-unique service names (the
 * name prefix constructor was added for this — example-side change only) and pins the
 * summary. Assertions stay loose where other registry activity could linger: the run
 * uses unique names, but the registry is shared state on a long-lived Redis.
 * Reachability-gated like the other example tests.
 */
class ServiceRegistryExampleTest {

    private static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
    private static final String UUID_HEX = java.util.UUID.randomUUID().toString().substring(0, 8);

    @Test
    void fullRunCoversDiscoveryFailureRecoveryAndLoadBalancing() throws Exception {
        Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL + " — skipping registry example");

        RedissonClient redisson = redisson();
        try {
            ServiceRegistryExample.Summary summary =
                    new ServiceRegistryExample("examples-reg-test-" + UUID_HEX + "-").runExample();

            assertTrue(summary.discoveredUsers() >= 1, "the registered user-service must be discoverable");
            assertTrue(summary.discoveredPayments() >= 1, "the registered payment-service must be discoverable");
            assertTrue(summary.healthyAfterFailure() <= summary.healthyAfterRecovery(),
                    "recovery must not leave fewer healthy payment instances than the failure state");
            assertTrue(summary.healthyAfterRecovery() >= 1,
                    "after simulateRecovery the payment instance must be healthy again");
            assertTrue(summary.loadBalancedDistinctInstances() >= 1,
                    "load balancing must have selected at least one healthy instance");
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
