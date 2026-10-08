package io.github.cuihairu.redis.streaming.benchmark;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Small-scale smoke run of the benchmark suite against a real Redis — keeps the harness
 * honest (measured code paths must stay functional) without adding meaningful runtime to CI.
 */
@Tag("integration")
class BenchmarkSmokeIntegrationTest {

    private static final String REDIS_URL = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");

    private static RedissonClient client() {
        Config config = new Config();
        config.useSingleServer().setAddress(REDIS_URL);
        return Redisson.create(config);
    }

    private static boolean reachable() {
        try (java.net.Socket s = new java.net.Socket()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("://([^/:]+):(\\d+)").matcher(REDIS_URL);
            if (!m.find()) {
                return false;
            }
            s.connect(new java.net.InetSocketAddress(m.group(1), Integer.parseInt(m.group(2))), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void mqBenchmarkRunsAndMeasures() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL);
        RedissonClient redis = client();
        String runId = "it-bench-mq-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            BenchmarkResult result = new MqThroughputBenchmark().run(redis, runId, 200, 32);
            assertEquals(200, result.operations());
            assertTrue(result.opsPerSec() > 0, "throughput must be positive");
            assertTrue(result.elapsedMs() > 0);
        } finally {
            try {
                redis.getKeys().deleteByPattern("*" + runId + "*");
            } catch (Exception ignore) {
            }
            redis.shutdown();
        }
    }

    @Test
    void checkpointBenchmarkRunsAndMeasures() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(reachable(), "no reachable Redis at " + REDIS_URL);
        RedissonClient redis = client();
        String runId = "it-bench-cp-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            BenchmarkResult result = new CheckpointDurationBenchmark().run(redis, runId, 2, 25);
            assertEquals(2, result.operations());
            assertTrue(result.p50Ms() >= 0);
        } finally {
            try {
                redis.getKeys().deleteByPattern("*" + runId + "*");
            } catch (Exception ignore) {
            }
            redis.shutdown();
        }
    }
}
