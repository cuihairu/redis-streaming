package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Redis coverage of the defer-ack checkpoint path: {@code DeferredAcks.ackAll}/clear,
 * stream-id parsing/comparison against commit frontiers and frontier advancement.
 */
@Tag("integration")
class RuntimeDeferAckAckAllIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void deferAckThenCheckpointAcksAndWritesFrontier() throws Exception {
        String runId = "it-rt-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = runId + "-t";
        RedissonClient redis = client();
        try {
            RedisRuntimeConfig cfg = base(runId);
            List<String> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic(topic, "g").map(m -> (String) m.getPayload()).addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                producer.send(topic, "k1", "v1").get(5, TimeUnit.SECONDS);
                producer.send(topic, "k2", "v2").get(5, TimeUnit.SECONDS);
                awaitSize(out, 2);

                Checkpoint cp = job.triggerCheckpointNow();
                assertNotNull(cp, "checkpoint must succeed so deferred acks run");

                RMap<String, String> frontier = redis.getMap(StreamKeys.commitFrontier(topic, 0));
                long deadline = System.currentTimeMillis() + 5_000;
                while (frontier.get("g") == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                String committed = frontier.get("g");
                assertNotNull(committed, "ackAll must advance the commit frontier");
                assertTrue(committed.contains("-"), "frontier should store a stream id");
                producer.close();
            }
        } finally {
            cleanup(redis, runId, topic);
        }
    }

    @Test
    void deferAckWithoutAckOnCheckpointClearsDeferredStateOnly() throws Exception {
        String runId = "it-rt-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = runId + "-t";
        RedissonClient redis = client();
        try {
            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                    .jobName(runId + "-job")
                    .stateKeyPrefix("streaming:" + runId)
                    .deferAckUntilCheckpoint(true)
                    .ackDeferredMessagesOnCheckpoint(false)
                    .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                    .build();
            List<String> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic(topic, "g").map(m -> (String) m.getPayload()).addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                producer.send(topic, "k1", "v1").get(5, TimeUnit.SECONDS);
                awaitSize(out, 1);

                Checkpoint cp = job.triggerCheckpointNow();
                assertNotNull(cp, "checkpoint must succeed so the deferred set is cleared");

                RMap<String, String> frontier = redis.getMap(StreamKeys.commitFrontier(topic, 0));
                assertNull(frontier.get("g"), "clear() path must not ack nor advance the frontier");
                producer.close();
            }
        } finally {
            cleanup(redis, runId, topic);
        }
    }

    @Test
    void ackAllComparesAgainstExistingFrontierValues() throws Exception {
        String runId = "it-rt-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = runId + "-t";
        RedissonClient redis = client();
        try {
            // Frontier seeds exceed real stream ids; keep group bootstrap at its default so the
            // messages still flow and only the ackAll comparison observes the seeded frontier.
            RedisRuntimeConfig cfg = baseNoFrontierRestore(runId);
            RMap<String, String> frontier = redis.getMap(StreamKeys.commitFrontier(topic, 0));
            frontier.put("g", "9999999999999-9");

            List<String> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic(topic, "g").map(m -> (String) m.getPayload()).addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                producer.send(topic, "k1", "v1").get(5, TimeUnit.SECONDS);
                awaitSize(out, 1);

                Checkpoint cp = job.triggerCheckpointNow();
                assertNotNull(cp);
                assertEquals("9999999999999-9", frontier.get("g"),
                        "lower message ids must not move the frontier backwards");
                producer.close();
            }
        } finally {
            cleanup(redis, runId, topic);
        }
    }

    @Test
    void ackAllFallsBackToLexicographicCompareOnGarbageFrontier() throws Exception {
        String runId = "it-rt-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = runId + "-t";
        RedissonClient redis = client();
        try {
            RedisRuntimeConfig cfg = baseNoFrontierRestore(runId);
            RMap<String, String> frontier = redis.getMap(StreamKeys.commitFrontier(topic, 0));
            frontier.put("g", "zzz-garbage");

            List<String> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic(topic, "g").map(m -> (String) m.getPayload()).addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                producer.send(topic, "k1", "v1").get(5, TimeUnit.SECONDS);
                awaitSize(out, 1);

                Checkpoint cp = job.triggerCheckpointNow();
                assertNotNull(cp);
                assertEquals("zzz-garbage", frontier.get("g"),
                        "unparsable frontier falls back to lexicographic compare and must not be clobbered");
                producer.close();
            }
        } finally {
            cleanup(redis, runId, topic);
        }
    }

    @Test
    void periodicDeferAckCheckpointsKeepFlowing() throws Exception {
        String runId = "it-rt-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = runId + "-t";
        RedissonClient redis = client();
        try {
            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                    .jobName(runId + "-job")
                    .stateKeyPrefix("streaming:" + runId)
                    .deferAckUntilCheckpoint(true)
                    .ackDeferredMessagesOnCheckpoint(true)
                    .checkpointInterval(java.time.Duration.ofMillis(300))
                    .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).consumerPollTimeoutMs(200).build())
                    .build();
            List<String> out = new CopyOnWriteArrayList<>();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic(topic, "g").map(m -> (String) m.getPayload()).addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                for (int i = 0; i < 3; i++) {
                    producer.send(topic, "k" + i, "v" + i).get(5, TimeUnit.SECONDS);
                }
                awaitSize(out, 3);

                // periodic checkpoints must eventually ack deferred messages and write the frontier
                RMap<String, String> frontier = redis.getMap(StreamKeys.commitFrontier(topic, 0));
                long deadline = System.currentTimeMillis() + 15_000;
                while (frontier.get("g") == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
                assertNotNull(frontier.get("g"), "periodic checkpoint must run the ackAll path");
                assertNotNull(job.getLatestCheckpoint());
                Map<String, Object> diag = job.diagnostics();
                assertEquals(1, diag.get("consumerCount"));
                producer.close();
            }
        } finally {
            cleanup(redis, runId, topic);
        }
    }

    private static RedisRuntimeConfig base(String runId) {
        return RedisRuntimeConfig.builder()
                .jobName(runId + "-job")
                .stateKeyPrefix("streaming:" + runId)
                .deferAckUntilCheckpoint(true)
                .ackDeferredMessagesOnCheckpoint(true)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
    }

    private static RedisRuntimeConfig baseNoFrontierRestore(String runId) {
        return RedisRuntimeConfig.builder()
                .jobName(runId + "-job")
                .stateKeyPrefix("streaming:" + runId)
                .deferAckUntilCheckpoint(true)
                .ackDeferredMessagesOnCheckpoint(true)
                .restoreConsumerGroupFromCommitFrontier(false)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
    }

    private static void awaitSize(List<String> out, int size) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (out.size() < size && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(size, out.size(), "pipeline must process messages; saw " + out);
    }

    private static void cleanup(RedissonClient redis, String runId, String topic) {
        redis.getKeys().deleteByPattern(StreamKeys.streamPrefix() + ":" + topic + "*");
        redis.getKeys().deleteByPattern(StreamKeys.controlPrefix() + ":*" + topic + "*");
        redis.getKeys().deleteByPattern(StreamKeys.controlPrefix() + ":*" + runId + "*");
        redis.getKeys().deleteByPattern("streaming:" + runId + "*");
        redis.shutdown();
    }
}
