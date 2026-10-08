package io.github.cuihairu.redis.streaming.benchmark;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Benchmark entry point. Runs the benchmark suite against a live Redis and prints one line
 * per benchmark:
 *
 * <pre>
 * REDIS_URL=redis://127.0.0.1:6379 \\
 * BENCH_MESSAGES=5000 BENCH_PAYLOAD_BYTES=0 BENCH_CHECKPOINTS=5 BENCH_CHECKPOINT_MESSAGES=100 \\
 * ./gradlew :benchmark:run
 * </pre>
 */
public final class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        int messages = intEnv("BENCH_MESSAGES", 5000);
        int payloadBytes = intEnv("BENCH_PAYLOAD_BYTES", 0);
        int checkpoints = intEnv("BENCH_CHECKPOINTS", 5);
        int checkpointMessages = intEnv("BENCH_CHECKPOINT_MESSAGES", 100);

        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redis = Redisson.create(config);
        String runId = "bench-" + UUID.randomUUID().toString().substring(0, 8);

        System.out.println("redis=" + redisUrl + " messages=" + messages
                + " payloadBytes=" + payloadBytes + " checkpoints=" + checkpoints);
        try {
            List<BenchmarkResult> results = new ArrayList<>();
            results.add(new MqThroughputBenchmark().run(redis, runId + "-mq", messages, payloadBytes));
            results.add(new CheckpointDurationBenchmark().run(redis, runId + "-cp", checkpoints, checkpointMessages));
            System.out.println("------------------------------------------------------------");
            results.forEach(r -> System.out.println(r.toLine()));
            System.out.println("------------------------------------------------------------");
        } finally {
            try {
                redis.getKeys().deleteByPattern("*" + runId + "*");
            } catch (Exception ignore) {
            }
            redis.shutdown();
        }
    }

    private static int intEnv(String key, int defaultValue) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? defaultValue : Integer.parseInt(v.trim());
    }
}
