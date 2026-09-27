package io.github.cuihairu.redis.streaming.checkpoint;

import io.github.cuihairu.redis.streaming.storm.Storms;
import org.junit.jupiter.api.Test;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;

import static org.junit.jupiter.api.Assertions.*;

/** Module-wide grand storm: sweep every checkpoint main class with a timeout-guarded pass. */
class CheckpointGrandStormTest {

    @Test
    void sweepAllClasses() throws Exception {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        org.junit.jupiter.api.Assumptions.assumeTrue(reachable(redisUrl),
                "no reachable Redis at " + redisUrl + " — skipping live storm sweep");
        java.util.Map<Class<?>, Object> hints = new java.util.HashMap<>();
        org.redisson.config.Config redisCfg = new org.redisson.config.Config();
        redisCfg.useSingleServer().setAddress(redisUrl);
        org.redisson.api.RedissonClient real = org.redisson.Redisson.create(redisCfg);
        hints.put(org.redisson.api.RedissonClient.class, real);
        int total = Storms.grandStorm(DefaultCheckpoint.class, "io.github.cuihairu.redis.streaming.checkpoint", hints, 150);
        System.err.println("GRAND-REDIS checkpoint invocations=" + total);
        real.shutdown();
    }

    private static boolean reachable(String redisUrl) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("://([^/:]+):(\\d+)").matcher(redisUrl);
        String host = "127.0.0.1";
        int port = 6379;
        if (m.find()) {
            host = m.group(1);
            port = Integer.parseInt(m.group(2));
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
