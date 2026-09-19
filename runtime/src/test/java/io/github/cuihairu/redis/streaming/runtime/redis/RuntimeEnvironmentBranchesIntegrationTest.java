package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Branch coverage for environment paths: MDC sampling, defer-ack warning, frontier restore. */
@Tag("integration")
class RuntimeEnvironmentBranchesIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    @Test
    void mdcDisabledAndDeferAckWithoutPeriodicCheckpointStillRuns() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("br-mdc-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:branch:" + UUID.randomUUID().toString().substring(0, 6))
                .mdcEnabled(false)
                .deferAckUntilCheckpoint(true) // no checkpoint interval -> warns but must still run
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String topic = "br-topic-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            List<String> out = new CopyOnWriteArrayList<>();
            env.fromMqTopic(topic, "g").map(m -> (String) m.getPayload()).addSink(out::add);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                producer.send(topic, "k", "mdc-off").get(5, TimeUnit.SECONDS);
                long deadline = System.currentTimeMillis() + 10_000;
                while (out.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                assertEquals(List.of("mdc-off"), out);
                // no periodic checkpointing -> manual trigger still works
                Checkpoint cp = job.triggerCheckpointNow();
                assertNotNull(cp);
                producer.close();
            }
        } finally {
            redis.getKeys().deleteByPattern(cfg.getStateKeyPrefix() + "*");
            redis.shutdown();
        }
    }

    @Test
    void commitFrontierRestoreCreatesGroupAtFrontier() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("br-fr-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:branch:" + UUID.randomUUID().toString().substring(0, 6))
                .restoreConsumerGroupFromCommitFrontier(true)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String topic = "br-fr-topic-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            // seed partition 0 with two entries and an acked frontier via a raw group
            final MessageProducer[] producerHolder = new MessageProducer[1];
        RStream<String, Object> stream = redis.getStream(
                    io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, 0));
            StreamMessageId first = stream.add(StreamAddArgs.entries(Map.of("payload", "one")));
            StreamMessageId second = stream.add(StreamAddArgs.entries(Map.of("payload", "two")));
            stream.createGroup(org.redisson.api.stream.StreamCreateGroupArgs.name("g")
                    .id(new StreamMessageId(0, 0)).makeStream());
            stream.ack("g", first);
            stream.readGroup("g", "boot", org.redisson.api.stream.StreamReadGroupArgs.neverDelivered().count(10));
            // record a commit-frontier for (topic, g, p0) at `second`, then destroy the group:
            // the job must recreate the group at the frontier and only see NEWER entries.
            org.redisson.api.RMap<String, String> frontier = redis.getMap(
                    io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.commitFrontier(topic, 0));
            frontier.put("g", second.toString());
            stream.removeGroup("g");

            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            Map<String, Integer> seen = new ConcurrentHashMap<>();
            env.fromMqTopic(topic, "g").map(m -> (String) m.getPayload())
                    .addSink(v -> seen.merge(String.valueOf(v), 1, Integer::sum));
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                producerHolder[0] = mq.createProducer();
                producerHolder[0].send(topic, "k", "three").get(5, TimeUnit.SECONDS);
                long deadline = System.currentTimeMillis() + 10_000;
                // "two" was already delivered to 'boot' (pending) -> consumer group state exists;
                // restore-from-frontier must not throw and pipeline must run.
                while (seen.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
                assertTrue(seen.containsKey("three"), "job should pick up only post-frontier entries; saw " + seen);
                assertFalse(seen.containsKey("one"), "frontier-skipped history must not be redelivered; saw " + seen);
                producerHolder[0].close();
            }
        } finally {
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.getKeys().deleteByPattern(cfg.getStateKeyPrefix() + "*");
            redis.shutdown();
        }
    }

    @Test
    void drainTimeoutPathSettlesQuickly() throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("br-drain-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:branch:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointDrainTimeout(Duration.ofMillis(50))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
        String topic = "br-drain-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            env.fromMqTopic(topic, "g").map(m -> (String) m.getPayload())
                    .addSink(v -> {
                        try {
                            Thread.sleep(300); // keep in-flight > drain timeout so the timeout branch runs
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                producer.send(topic, "k", "slow").get(5, TimeUnit.SECONDS);
                Thread.sleep(600);
                Checkpoint cp = job.triggerCheckpointNow(); // may be null on drain failure; must not hang
                long start = System.currentTimeMillis();
                job.triggerCheckpointNow();
                assertTrue(System.currentTimeMillis() - start < 10_000);
                assertNotNull(cp == null ? cp : cp);
                producer.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.getKeys().deleteByPattern(cfg.getStateKeyPrefix() + "*");
            redis.shutdown();
        }
    }
}
