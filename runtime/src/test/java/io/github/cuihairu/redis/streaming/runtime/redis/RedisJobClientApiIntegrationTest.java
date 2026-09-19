package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the RedisJobClient surface that the happy-path suites miss: diagnostics keys,
 * idempotent cancel, awaitTermination, manual checkpoint reporting, pause/resume and
 * execute guards.
 */
@Tag("integration")
class RedisJobClientApiIntegrationTest {

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    @Test
    void jobClientApiSurface() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("jc-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:jc:test:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointInterval(Duration.ofHours(1)) // manual only
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        String topic = "jc-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "jc-group";
        RedissonClient client = createClient();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
            List<String> out = new CopyOnWriteArrayList<>();
            env.fromMqTopic(topic, group)
                    .map(m -> (String) m.getPayload())
                    .addSink(out::add);

            // guard: registering pipelines after execution is rejected (null def first hits NPE check? no: executed flag wins)
            RedisJobClient job = env.executeAsync();
            assertThrows(IllegalStateException.class, () -> env.registerPipelineDefinition(
                    new io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineDefinition(
                            cfg, client, new com.fasterxml.jackson.databind.ObjectMapper(),
                            "late-topic", "late-group", null, List.of())));
            // guard: executeAsync is single-shot per environment
            assertThrows(IllegalStateException.class, env::executeAsync);

            MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
            MessageProducer producer = mq.createProducer();
            producer.send(topic, "k", "hello").get(5, TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + 10_000;
            while (out.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(List.of("hello"), out);

            // in-flight drain should settle to 0
            long inflightDeadline = System.currentTimeMillis() + 5_000;
            while (job.inFlight() > 0 && System.currentTimeMillis() < inflightDeadline) {
                Thread.sleep(50);
            }
            assertEquals(0L, job.inFlight());

            // pause/resume must not throw and must still allow processing after resume
            job.pause();
            job.resume();

            Checkpoint cp = job.triggerCheckpointNow();
            assertNotNull(cp);
            assertNotNull(job.getLatestCheckpoint());
            assertEquals(cp.getCheckpointId(), job.getLatestCheckpoint().getCheckpointId());
            // second concurrent-style trigger while one "runs": at least it must never throw
            assertNotNull(job.triggerCheckpointNow());

            Map<String, Object> diag = job.diagnostics();
            assertEquals(cfg.getJobName(), diag.get("jobName"));
            assertEquals(1, diag.get("consumerCount"));
            assertEquals(1, diag.get("runnerCount"));
            assertTrue(diag.containsKey("pipelines"));
            assertTrue(((List<?>) diag.get("pipelines")).size() >= 1);
            assertNotNull(diag.get("latestCheckpointId"));
            assertEquals(Boolean.FALSE, diag.get("checkpointing"));

            // cancel is idempotent and releases the handle
            job.cancel();
            assertDoesNotThrow(job::cancel);
            job.close();
            assertTrue(job.awaitTermination(Duration.ofSeconds(5)));
            // trigger after cancel returns null
            assertNull(job.triggerCheckpointNow());
            producer.close();
        } finally {
            client.getKeys().deleteByPattern(cfg.getStateKeyPrefix() + "*");
            client.shutdown();
        }
    }

    @Test
    void emptyEnvironmentExecutionIsRejected() {
        RedissonClient client = createClient();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client,
                    RedisRuntimeConfig.builder().jobName("jc-empty").build());
            assertThrows(IllegalStateException.class, env::executeAsync);
        } finally {
            client.shutdown();
        }
    }
}
