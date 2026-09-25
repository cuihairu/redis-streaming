package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.api.stream.CheckpointAwareSink;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Periodic checkpointing under traffic with deferred acks, a failing sink commit (abort
 * branch) and a second job restoring from the latest checkpoint, on real Redis.
 */
@Tag("integration")
class RuntimeCheckpointChaosIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    static final class CountingSink implements CheckpointAwareSink<String> {
        private static final long serialVersionUID = 1L;
        final CopyOnWriteArrayList<String> values = new CopyOnWriteArrayList<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger completes = new AtomicInteger();
        final AtomicInteger aborts = new AtomicInteger();
        final AtomicBoolean failNextComplete = new AtomicBoolean();
        volatile long lastCompletedId = -1;

        @Override
        public void invoke(String value) {
            values.add(value);
        }

        @Override
        public void onCheckpointStart(long checkpointId) {
            starts.incrementAndGet();
        }

        @Override
        public void onCheckpointComplete(long checkpointId) throws Exception {
            if (failNextComplete.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated commit failure");
            }
            completes.incrementAndGet();
            lastCompletedId = checkpointId;
        }

        @Override
        public void onCheckpointAbort(long checkpointId, Throwable cause) {
            aborts.incrementAndGet();
        }
    }

    @Test
    void periodicCheckpointsWithAbortAndRestore() throws Exception {
        String statePrefix = "streaming:cchk:" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "cchk-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "g";
        RedissonClient redis = client();
        CountingSink sink = new CountingSink();
        try {
            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                    .jobName("cchk-job-" + UUID.randomUUID().toString().substring(0, 6))
                    .stateKeyPrefix(statePrefix)
                    .checkpointInterval(Duration.ofMillis(700))
                    .deferAckUntilCheckpoint(true)
                    .ackDeferredMessagesOnCheckpoint(true)
                    .pipelineParallelism(2)
                    .mqOptions(MqOptions.builder().workerThreads(2).schedulerThreads(1).consumerPollTimeoutMs(200).build())
                    .build();

            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic(topic, group).map(m -> (String) m.getPayload()).addSink(sink);
            RedisJobClient job = env.executeAsync();

            MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
            MessageProducer producer = mq.createProducer();
            for (int i = 0; i < 4; i++) {
                producer.send(topic, "k" + i, "a-" + i).get(5, TimeUnit.SECONDS);
            }
            long deadline = System.currentTimeMillis() + 15_000;
            while (sink.values.size() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(4, sink.values.size());

            // abort branch: next scheduled sink commit fails
            sink.failNextComplete.set(true);
            deadline = System.currentTimeMillis() + 20_000;
            while (sink.aborts.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(150);
            }
            assertTrue(sink.aborts.get() >= 1, "scheduled checkpoint should hit the abort path");
            assertTrue(sink.starts.get() >= 2, "periodic checkpoints must have started, got " + sink.starts.get());
            assertNotNull(job.getLatestCheckpoint());

            // messages must still flow after the abort (acks resume)
            producer.send(topic, "k-x", "a-after").get(5, TimeUnit.SECONDS);
            deadline = System.currentTimeMillis() + 15_000;
            while (sink.values.size() < 5 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(5, sink.values.size());
            job.cancel();
            producer.close();

            // second job restores from the latest checkpoint and continues on the same group
            CountingSink sink2 = new CountingSink();
            RedisRuntimeConfig cfg2 = RedisRuntimeConfig.builder()
                    .jobName(cfg.getJobName())
                    .stateKeyPrefix(statePrefix)
                    .restoreFromLatestCheckpoint(true)
                    .checkpointInterval(Duration.ofHours(1))
                    .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).consumerPollTimeoutMs(200).build())
                    .build();
            RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(redis, cfg2);
            env2.fromMqTopic(topic, group).map(m -> (String) m.getPayload()).addSink(sink2);
            try (RedisJobClient job2 = env2.executeAsync()) {
                MessageQueueFactory mq2 = new MessageQueueFactory(redis, cfg2.getMqOptions());
                MessageProducer p2 = mq2.createProducer();
                p2.send(topic, "k-y", "b-1").get(5, TimeUnit.SECONDS);
                // restored job rebuilds the consumer group and drains state first; under full-suite
                // load 15s was flaky (failed twice in CI/local) — allow a generous bounded wait
                deadline = System.currentTimeMillis() + 45_000;
                while (sink2.values.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
                assertTrue(sink2.values.contains("b-1"), "restored job must consume new traffic");
                Checkpoint cp = job2.triggerCheckpointNow();
                assertNotNull(cp);
                p2.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.getKeys().deleteByPattern(statePrefix + "*");
            redis.shutdown();
        }
    }
}
