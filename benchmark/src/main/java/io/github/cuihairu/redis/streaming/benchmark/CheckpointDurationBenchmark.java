package io.github.cuihairu.redis.streaming.benchmark;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisJobClient;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisStreamExecutionEnvironment;
import org.redisson.api.RedissonClient;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Checkpoint duration benchmark: drives a Redis-runtime pipeline, sends
 * {@code messagesPerRound} messages between checkpoints and measures the wall time of
 * {@code triggerCheckpointNow()} (stop-the-world drain + state snapshot + sink coordination)
 * across {@code rounds} checkpoints.
 */
public final class CheckpointDurationBenchmark {

    public BenchmarkResult run(RedissonClient redis, String jobName, int rounds, int messagesPerRound) throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder().jobName(jobName).build();
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
        String topic = jobName + "-topic";
        env.fromMqTopic(topic, "bench-group").map(m -> String.valueOf(m.getPayload())).addSink(v -> { });

        long[] durations = new long[rounds];
        try (RedisJobClient job = env.executeAsync()) {
            MessageQueueFactory mq = new MessageQueueFactory(redis);
            MessageProducer producer = mq.createProducer();
            for (int round = 0; round < rounds; round++) {
                for (int i = 0; i < messagesPerRound; i++) {
                    producer.send(topic, "k", "v-" + round + "-" + i).get(10, TimeUnit.SECONDS);
                }
                long start = System.nanoTime();
                Checkpoint cp = job.triggerCheckpointNow();
                durations[round] = (System.nanoTime() - start) / 1_000_000;
                if (cp == null) {
                    throw new IllegalStateException("checkpoint failed at round " + round);
                }
                Thread.sleep(50); // let the next epoch settle before the next measurement
            }
            producer.close();
        }

        long totalMs = Arrays.stream(durations).sum();
        return new BenchmarkResult("checkpoint drain+snapshot", rounds, totalMs,
                rounds * 1000.0 / Math.max(totalMs, 1),
                Percentiles.p50(durations), Percentiles.p95(durations), Percentiles.p99(durations));
    }
}
