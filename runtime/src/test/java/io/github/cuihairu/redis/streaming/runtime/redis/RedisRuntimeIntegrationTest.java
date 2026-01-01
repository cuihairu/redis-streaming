package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.CheckpointAwareSink;
import io.github.cuihairu.redis.streaming.api.stream.IdempotentRecord;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedProcessFunction;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.DeadLetterQueueManager;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.runtime.redis.sink.RedisIdempotentListSink;
import io.github.cuihairu.redis.streaming.runtime.redis.sink.RedisCheckpointedIdempotentListSink;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.api.StreamMessageId;
import org.redisson.api.RMap;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RKeys;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import org.redisson.api.RSet;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class RedisRuntimeIntegrationTest {

    @Test
    void consumesFromMqAndInvokesSink() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        DataStream<String> stream = env.fromMqTopic(topic, group)
                .map(m -> (String) m.getPayload());

        stream.addSink(v -> {
            out.add(v);
            latch.countDown();
        });

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k2", "b").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k3", "c").get(5, TimeUnit.SECONDS);

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(3, out.size());
            assertTrue(out.containsAll(List.of("a", "b", "c")));
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void keyedProcessUsesRedisKeyedValueState() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        List<String> seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        DataStream<String> base = env.fromMqTopic(topic, group).map(m -> (String) m.getPayload());
        KeyedStream<String, String> keyed = base.keyBy(v -> v);

        StateDescriptor<Integer> desc = new StateDescriptor<>("cnt", Integer.class, 0);
        ValueState<Integer> cnt = keyed.getState(desc);

        keyed.<String>process((key, value, ctx, out) -> {
            Integer c = cnt.value();
            if (c == null) c = 0;
            c = c + 1;
            cnt.update(c);
            out.collect(key + ":" + c);
        }).addSink(v -> {
            seen.add(v);
            latch.countDown();
        });

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);
            producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);
            producer.send(topic, "b", "b").get(5, TimeUnit.SECONDS);

            assertTrue(latch.await(15, TimeUnit.SECONDS));
            assertEquals(3, seen.size());

            // Order is not guaranteed; verify counts per key.
            List<String> a = new ArrayList<>();
            List<String> b = new ArrayList<>();
            for (String s : seen) {
                if (s.startsWith("a:")) a.add(s);
                if (s.startsWith("b:")) b.add(s);
            }
            assertEquals(2, a.size());
            assertEquals(1, b.size());
            assertTrue(a.contains("a:1"));
            assertTrue(a.contains("a:2"));
            assertTrue(b.contains("b:1"));
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void redisKeyedStateCanHaveTtlWhenConfigured() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .jobInstanceId("inst-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6))
                .stateTtl(Duration.ofSeconds(30))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        java.util.concurrent.atomic.AtomicInteger pid = new java.util.concurrent.atomic.AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);

        KeyedStream<String, String> keyed = env.fromMqTopicWithId("s1", topic, group)
                .map(m -> {
                    String p = m.getHeaders() == null ? null : m.getHeaders().get(io.github.cuihairu.redis.streaming.mq.MqHeaders.PARTITION_ID);
                    pid.set(p == null ? -1 : Integer.parseInt(p));
                    return (String) m.getPayload();
                })
                .keyBy(v -> v);

        StateDescriptor<Integer> desc = new StateDescriptor<>("cnt", Integer.class, 0);
        ValueState<Integer> cnt = keyed.getState(desc);

        keyed.<String>process((key, value, ctx, out) -> {
            cnt.update(cnt.value() + 1);
            out.collect(key);
        }).addSink(v -> latch.countDown());

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);
            assertTrue(latch.await(15, TimeUnit.SECONDS));

            int partitionId = pid.get();
            assertTrue(partitionId >= 0);
            String operatorId = "s1-keyBy-1";
            String redisKey = cfg.getStateKeyPrefix() + ":" + cfg.getJobName() +
                    ":cg:" + group +
                    ":topic:" + topic +
                    ":p:" + partitionId +
                    ":state:" + operatorId + ":" + desc.getName();

            RKeys keys = client.getKeys();
            Long ttl = keys.remainTimeToLive(redisKey);
            assertNotNull(ttl);
            assertTrue(ttl > 0, "expected positive TTL on state key");
            assertTrue(ttl <= 30000L, "ttl should not exceed configured ttl (rough check)");
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void restoresConsumerGroupFromCommitFrontierWhenGroupDeleted() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .jobInstanceId("inst-" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .restoreConsumerGroupFromCommitFrontier(true)
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        try {
            // First run: consume 3 messages
            RedisStreamExecutionEnvironment env1 = RedisStreamExecutionEnvironment.create(client, cfg);
            List<String> out1 = new CopyOnWriteArrayList<>();
            CountDownLatch latch1 = new CountDownLatch(3);

            env1.fromMqTopic(topic, group)
                    .map(m -> (String) m.getPayload())
                    .addSink(v -> {
                        out1.add(v);
                        latch1.countDown();
                    });

            try (RedisJobClient job1 = env1.executeAsync()) {
                producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
                producer.send(topic, "k2", "b").get(5, TimeUnit.SECONDS);
                producer.send(topic, "k3", "c").get(5, TimeUnit.SECONDS);
                assertTrue(latch1.await(15, TimeUnit.SECONDS));
                assertEquals(3, out1.size());
                assertTrue(out1.containsAll(List.of("a", "b", "c")));
            }

            // Wait commit frontier present (partition 0 in tests)
            String frontierKey = StreamKeys.commitFrontier(topic, 0);
            @SuppressWarnings("rawtypes")
            RMap frontier = client.getMap(frontierKey);
            long deadline = System.currentTimeMillis() + 5000L;
            String committed;
            while (true) {
                Object v = frontier.get(group);
                committed = v == null ? null : String.valueOf(v);
                if (committed != null && !committed.isBlank()) break;
                if (System.currentTimeMillis() > deadline) break;
                Thread.sleep(50);
            }
            assertNotNull(committed, "commit frontier should be recorded after successful handling");

            // Simulate ops error: consumer group deleted
            assertTrue(mq.createAdmin().deleteConsumerGroup(topic, group));

            // Second run: should NOT reprocess old messages after group deletion
            RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(client, cfg);
            List<String> out2 = new CopyOnWriteArrayList<>();
            CountDownLatch latch2 = new CountDownLatch(1);

            env2.fromMqTopic(topic, group)
                    .map(m -> (String) m.getPayload())
                    .addSink(v -> {
                        out2.add(v);
                        latch2.countDown();
                    });

            try (RedisJobClient job2 = env2.executeAsync()) {
                assertFalse(latch2.await(2, TimeUnit.SECONDS), "should not reprocess old messages after group deletion");
                producer.send(topic, "k4", "d").get(5, TimeUnit.SECONDS);
                assertTrue(latch2.await(15, TimeUnit.SECONDS));
                assertEquals(List.of("d"), out2);
            }
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void checkpointCanRestoreOffsetsAndState() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);
        String jobName = "rt-job-" + UUID.randomUUID().toString().substring(0, 6);
        String stateKeyPrefix = "streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6);
        String checkpointKeyPrefix = "streaming:runtime:checkpoint:test:" + UUID.randomUUID().toString().substring(0, 6) + ":";

        RedisRuntimeConfig cfg1 = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId("inst-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix(stateKeyPrefix)
                .checkpointKeyPrefix(checkpointKeyPrefix)
                .checkpointInterval(Duration.ofMillis(200))
                .checkpointsToKeep(3)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg1.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisCheckpointStorage storage = new RedisCheckpointStorage(client, cfg1.getCheckpointKeyPrefix() + cfg1.getJobName() + ":");

        try {
            RedisStreamExecutionEnvironment env1 = RedisStreamExecutionEnvironment.create(client, cfg1);

            List<Integer> out1 = new CopyOnWriteArrayList<>();
            CountDownLatch latch1 = new CountDownLatch(3);

            KeyedStream<String, String> keyed = env1.fromMqTopicWithId("s1", topic, group)
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k");

            StateDescriptor<Integer> desc = new StateDescriptor<>("cnt", Integer.class, 0);
            ValueState<Integer> cnt = keyed.getState(desc);

            keyed.<Integer>process((key, value, ctx, out) -> {
                int next = cnt.value() + 1;
                cnt.update(next);
                out.collect(next);
            }).addSink(v -> {
                out1.add(v);
                latch1.countDown();
            });

            String lastId;
            try (RedisJobClient job1 = env1.executeAsync()) {
                producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
                producer.send(topic, "k2", "b").get(5, TimeUnit.SECONDS);
                lastId = producer.send(topic, "k3", "c").get(5, TimeUnit.SECONDS);
                assertTrue(latch1.await(20, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2, 3), out1);

                // Wait commit frontier to catch up (partition 0 in tests)
                String frontierKey = StreamKeys.commitFrontier(topic, 0);
                @SuppressWarnings("rawtypes")
                RMap frontier = client.getMap(frontierKey);
                long deadline = System.currentTimeMillis() + 10_000L;
                while (true) {
                    Object v = frontier.get(group);
                    String committed = v == null ? null : String.valueOf(v);
                    if (lastId.equals(committed)) break;
                    if (System.currentTimeMillis() > deadline) break;
                    Thread.sleep(50);
                }

                // Wait a checkpoint that includes the committed id
                deadline = System.currentTimeMillis() + 15_000L;
                while (true) {
                    Checkpoint cp = storage.getLatestCheckpoint();
                    if (cp != null && cp.isCompleted()) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, java.util.Map<Integer, String>> offsets =
                                cp.getStateSnapshot().getState("runtime:offsets");
                        if (offsets != null) {
                            java.util.Map<Integer, String> p0 = offsets.get(topic + "|" + group);
                            if (p0 != null && lastId.equals(p0.get(0))) {
                                break;
                            }
                        }
                    }
                    if (System.currentTimeMillis() > deadline) {
                        fail("checkpoint not observed");
                    }
                    Thread.sleep(100);
                }
            }

            // Simulate data loss / ops: delete group + delete state keys
            assertTrue(mq.createAdmin().deleteConsumerGroup(topic, group));
            String indexKey = stateKeyPrefix + ":" + jobName + ":stateKeys";
            RSet<String> index = client.getSet(indexKey, StringCodec.INSTANCE);
            for (String k : index.readAll()) {
                try {
                    client.getKeys().delete(k);
                } catch (Exception ignore) {
                }
            }
            index.clear();

            RedisRuntimeConfig cfg2 = RedisRuntimeConfig.builder()
                    .jobName(jobName)
                    .jobInstanceId("inst-" + UUID.randomUUID().toString().substring(0, 6))
                    .stateKeyPrefix(stateKeyPrefix)
                    .checkpointKeyPrefix(checkpointKeyPrefix)
                    .checkpointInterval(Duration.ZERO)
                    .restoreFromLatestCheckpoint(true)
                    .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                    .build();

            RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(client, cfg2);

            List<Integer> out2 = new CopyOnWriteArrayList<>();
            CountDownLatch latch2 = new CountDownLatch(1);

            KeyedStream<String, String> keyed2 = env2.fromMqTopicWithId("s1", topic, group)
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k");

            ValueState<Integer> cnt2 = keyed2.getState(desc);

            keyed2.<Integer>process((key, value, ctx, out) -> {
                int next = cnt2.value() + 1;
                cnt2.update(next);
                out.collect(next);
            }).addSink(v -> {
                out2.add(v);
                latch2.countDown();
            });

            try (RedisJobClient job2 = env2.executeAsync()) {
                assertFalse(latch2.await(2, TimeUnit.SECONDS), "should not replay old messages");
                producer.send(topic, "k4", "d").get(5, TimeUnit.SECONDS);
                assertTrue(latch2.await(20, TimeUnit.SECONDS));
                assertEquals(List.of(4), out2);
            }
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void manualCheckpointCanRestoreOffsetsAndState() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);
        String jobName = "rt-job-" + UUID.randomUUID().toString().substring(0, 6);
        String jobInstanceId = "inst-" + UUID.randomUUID().toString().substring(0, 6);
        String stateKeyPrefix = "streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6);
        String checkpointKeyPrefix = "streaming:runtime:checkpoint:test:" + UUID.randomUUID().toString().substring(0, 6) + ":";

        RedisRuntimeConfig cfg1 = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId(jobInstanceId)
                .stateKeyPrefix(stateKeyPrefix)
                .checkpointKeyPrefix(checkpointKeyPrefix)
                .checkpointInterval(Duration.ZERO)
                .checkpointsToKeep(3)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg1.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisCheckpointStorage storage = new RedisCheckpointStorage(client, cfg1.getCheckpointKeyPrefix() + cfg1.getJobName() + ":");

        try {
            RedisStreamExecutionEnvironment env1 = RedisStreamExecutionEnvironment.create(client, cfg1);

            List<Integer> out1 = new CopyOnWriteArrayList<>();
            CountDownLatch latch1 = new CountDownLatch(3);

            KeyedStream<String, String> keyed = env1.fromMqTopicWithId("s1", topic, group)
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k");

            StateDescriptor<Integer> desc = new StateDescriptor<>("cnt", Integer.class, 0);
            ValueState<Integer> cnt = keyed.getState(desc);

            keyed.<Integer>process((key, value, ctx, out) -> {
                int c = cnt.value();
                cnt.update(c + 1);
                out.collect(c + 1);
            }).addSink(v -> {
                out1.add(v);
                latch1.countDown();
            });

            Checkpoint cp1;
            try (RedisJobClient job1 = env1.executeAsync()) {
                producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
                producer.send(topic, "k2", "b").get(5, TimeUnit.SECONDS);
                producer.send(topic, "k3", "c").get(5, TimeUnit.SECONDS);

                assertTrue(latch1.await(20, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2, 3), out1);

                cp1 = job1.triggerCheckpointNow();
                assertNotNull(cp1, "expected manual checkpoint to be created");
            }

            // Ensure checkpoint stored
            long deadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < deadline) {
                Checkpoint latest = storage.getLatestCheckpoint();
                if (latest != null && latest.getCheckpointId() >= cp1.getCheckpointId()) {
                    break;
                }
                Thread.sleep(50);
            }
            assertNotNull(storage.getLatestCheckpoint());

            // Stop and restart from checkpoint: should not replay old messages, state continues from 3 -> 4
            RedisRuntimeConfig cfg2 = RedisRuntimeConfig.builder()
                    .jobName(jobName)
                    .jobInstanceId(jobInstanceId)
                    .stateKeyPrefix(stateKeyPrefix)
                    .checkpointKeyPrefix(checkpointKeyPrefix)
                    .checkpointInterval(Duration.ZERO)
                    .restoreFromLatestCheckpoint(true)
                    .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                    .build();

            RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(client, cfg2);
            List<Integer> out2 = new CopyOnWriteArrayList<>();
            CountDownLatch latch2 = new CountDownLatch(1);

            KeyedStream<String, String> keyed2 = env2.fromMqTopicWithId("s1", topic, group)
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> "k");
            ValueState<Integer> cnt2 = keyed2.getState(desc);

            keyed2.<Integer>process((key, value, ctx, out) -> {
                int c = cnt2.value();
                cnt2.update(c + 1);
                out.collect(c + 1);
            }).addSink(v -> {
                out2.add(v);
                latch2.countDown();
            });

            try (RedisJobClient job2 = env2.executeAsync()) {
                assertFalse(latch2.await(2, TimeUnit.SECONDS), "should not replay old messages");
                producer.send(topic, "k4", "d").get(5, TimeUnit.SECONDS);
                assertTrue(latch2.await(20, TimeUnit.SECONDS));
                assertEquals(List.of(4), out2);
            }
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void jobClientCanPauseAndResumeConsumption() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .jobInstanceId("inst-" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).consumerPollTimeoutMs(100).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        env.fromMqTopic(topic, group)
                .map(m -> (String) m.getPayload())
                .addSink(v -> {
                    out.add(v);
                    latch.countDown();
                });

        try (RedisJobClient job = env.executeAsync()) {
            job.pause();
            // Give consumer workers a moment to observe pause (poll loop checks pause between reads).
            Thread.sleep(200);
            producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
            assertFalse(latch.await(800, TimeUnit.MILLISECONDS), "paused job should not consume");
            assertTrue(out.isEmpty());

            job.resume();
            assertTrue(latch.await(15, TimeUnit.SECONDS), "resumed job should consume");
            assertEquals(List.of("a"), out);
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void endToEndCheckpointCanCoordinateSinkAndAcks() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);
        String jobName = "rt-job-" + UUID.randomUUID().toString().substring(0, 6);
        String jobInstanceId = "inst-" + UUID.randomUUID().toString().substring(0, 6);
        String checkpointKeyPrefix = "streaming:runtime:checkpoint:test:" + UUID.randomUUID().toString().substring(0, 6) + ":";

        String committedCounterKey = "rt-test:sink-committed:" + UUID.randomUUID().toString().substring(0, 8);
        RAtomicLong committed = client.getAtomicLong(committedCounterKey);
        committed.set(0);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId(jobInstanceId)
                .checkpointKeyPrefix(checkpointKeyPrefix)
                .checkpointInterval(Duration.ZERO) // manual
                .restoreFromLatestCheckpoint(false)
                .deferAckUntilCheckpoint(true)
                .mqOptions(MqOptions.builder()
                        .workerThreads(1)
                        .schedulerThreads(1)
                        .consumerPollTimeoutMs(100)
                        .build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();
        MessageQueueAdmin admin = mq.createAdmin();

        RedisCheckpointStorage storage = new RedisCheckpointStorage(client, cfg.getCheckpointKeyPrefix() + cfg.getJobName() + ":");

        CountDownLatch processed = new CountDownLatch(1);

        RedisStreamExecutionEnvironment env1 = RedisStreamExecutionEnvironment.create(client, cfg);
        env1.fromMqTopic(topic, group)
                .map(m -> (String) m.getPayload())
                .addSink(new CheckpointAwareSink<String>() {
                    private final java.util.List<String> buffer = new java.util.concurrent.CopyOnWriteArrayList<>();

                    @Override
                    public void invoke(String value) {
                        buffer.add(value);
                        processed.countDown();
                    }

                    @Override
                    public void onCheckpointComplete(long checkpointId) {
                        long n = buffer.size();
                        if (n > 0) {
                            committed.addAndGet(n);
                            buffer.clear();
                        }
                    }
                });

        try (RedisJobClient job1 = env1.executeAsync()) {
            producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
            assertTrue(processed.await(15, TimeUnit.SECONDS), "expected message to be processed");

            assertEquals(0L, committed.get(), "sink should not commit before checkpoint");
            long pending = admin.getPendingCount(topic, group);
            assertTrue(pending >= 1, "expected pending when ack is deferred");

            Checkpoint cp = job1.triggerCheckpointNow();
            assertNotNull(cp);

            long deadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < deadline) {
                Checkpoint latest = storage.getLatestCheckpoint();
                if (latest != null && latest.getCheckpointId() >= cp.getCheckpointId()) {
                    break;
                }
                Thread.sleep(50);
            }
            assertNotNull(storage.getLatestCheckpoint());

            long pendingDeadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < pendingDeadline) {
                if (admin.getPendingCount(topic, group) <= 0) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(0L, admin.getPendingCount(topic, group), "expected pending cleared after checkpoint ack");
            assertEquals(1L, committed.get(), "sink should commit on checkpoint complete");
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void restorePrefersLatestSinkCommittedCheckpointWhenDeferAckEnabled() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);
        String jobName = "rt-job-" + UUID.randomUUID().toString().substring(0, 6);
        String checkpointKeyPrefix = "streaming:runtime:checkpoint:test:" + UUID.randomUUID().toString().substring(0, 6) + ":";

        RedisRuntimeConfig cfg1 = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId("inst-1-" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointKeyPrefix(checkpointKeyPrefix)
                .checkpointInterval(Duration.ZERO) // manual
                .restoreFromLatestCheckpoint(false)
                .deferAckUntilCheckpoint(true)
                .mqOptions(MqOptions.builder()
                        .workerThreads(1)
                        .schedulerThreads(1)
                        .consumerPollTimeoutMs(100)
                        .build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg1.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisCheckpointStorage storage = new RedisCheckpointStorage(client, cfg1.getCheckpointKeyPrefix() + cfg1.getJobName() + ":");

        CountDownLatch processed = new CountDownLatch(1);
        AtomicInteger checkpointCompletes = new AtomicInteger(0);

        RedisStreamExecutionEnvironment env1 = RedisStreamExecutionEnvironment.create(client, cfg1);
        env1.fromMqTopicWithId("s1", topic, group)
                .map(m -> (String) m.getPayload())
                .addSink(new CheckpointAwareSink<String>() {
                    @Override
                    public void invoke(String value) {
                        processed.countDown();
                    }

                    @Override
                    public void onCheckpointComplete(long checkpointId) {
                        int n = checkpointCompletes.incrementAndGet();
                        if (n == 2) {
                            throw new RuntimeException("fail checkpoint complete");
                        }
                    }
                });

        Checkpoint committed;
        Checkpoint uncommitted;
        try (RedisJobClient job1 = env1.executeAsync()) {
            producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
            assertTrue(processed.await(15, TimeUnit.SECONDS));

            committed = job1.triggerCheckpointNow();
            assertNotNull(committed);

            uncommitted = job1.triggerCheckpointNow();
            assertNotNull(uncommitted);
            assertTrue(uncommitted.getCheckpointId() > committed.getCheckpointId());

            Checkpoint latest = storage.getLatestCheckpoint();
            assertNotNull(latest);
            assertEquals(uncommitted.getCheckpointId(), latest.getCheckpointId());

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> meta = (java.util.Map<String, Object>) latest.getStateSnapshot().getState("runtime:meta");
            assertNotNull(meta);
            assertEquals(Boolean.FALSE, meta.get("sinkCommitted"));
        } finally {
            producer.close();
        }

        RedisRuntimeConfig cfg2 = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId("inst-2-" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointKeyPrefix(checkpointKeyPrefix)
                .checkpointInterval(Duration.ZERO)
                .restoreFromLatestCheckpoint(true)
                .deferAckUntilCheckpoint(true)
                .mqOptions(MqOptions.builder()
                        .workerThreads(1)
                        .schedulerThreads(1)
                        .consumerPollTimeoutMs(100)
                        .build())
                .build();

        java.util.concurrent.atomic.AtomicLong restoredId = new java.util.concurrent.atomic.AtomicLong(-1L);
        RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(client, cfg2);
        env2.fromMqTopicWithId("s1", topic, group)
                .map(m -> (String) m.getPayload())
                .addSink(new CheckpointAwareSink<String>() {
                    @Override
                    public void invoke(String value) {
                    }

                    @Override
                    public void onCheckpointRestore(long checkpointId) {
                        restoredId.set(checkpointId);
                    }
                });

        try (RedisJobClient job2 = env2.executeAsync()) {
            assertEquals(committed.getCheckpointId(), restoredId.get(),
                    "restore should pick latest sinkCommitted checkpoint, not latest checkpoint");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void keyedReduceAccumulatesPerKey() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        List<Integer> out = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        env.fromMqTopic(topic, group)
                .map(m -> Integer.parseInt((String) m.getPayload()))
                .keyBy(v -> v % 2 == 0 ? "even" : "odd")
                .reduce(Integer::sum)
                .addSink(v -> {
                    out.add(v);
                    latch.countDown();
                });

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "1").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k2", "2").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k3", "3").get(5, TimeUnit.SECONDS);

            assertTrue(latch.await(15, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 4), out);
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void keyedSumAccumulatesPerKey() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        List<Integer> out = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        env.fromMqTopic(topic, group)
                .map(m -> Integer.parseInt((String) m.getPayload()))
                .keyBy(v -> v % 2 == 0 ? "even" : "odd")
                .sum(Integer::intValue)
                .addSink(v -> {
                    out.add(v);
                    latch.countDown();
                });

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "1").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k2", "2").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k3", "3").get(5, TimeUnit.SECONDS);

            assertTrue(latch.await(15, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 4), out);
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void keyedMapPreservesKeyAssignment() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        List<Integer> out = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        env.fromMqTopic(topic, group)
                .map(m -> Integer.parseInt((String) m.getPayload()))
                .keyBy(v -> v % 2 == 0 ? "even" : "odd")
                .map(v -> v * 10)
                .reduce(Integer::sum)
                .addSink(v -> {
                    out.add(v);
                    latch.countDown();
                });

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "1").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k2", "2").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k3", "3").get(5, TimeUnit.SECONDS);

            assertTrue(latch.await(15, TimeUnit.SECONDS));
            assertEquals(List.of(10, 20, 40), out);
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void chainedKeyedMapDoesNotLeakKeyAcrossMessages() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        env.fromMqTopic(topic, group)
                .map(m -> (String) m.getPayload())
                .keyBy(v -> v)
                .map(v -> v) // first keyed-map sets ThreadLocal key for downstream
                .map(v -> v) // second keyed-map must not see stale key from previous record
                .<String>process((key, value, ctx, o) -> o.collect(key + "=" + value))
                .addSink(v -> {
                    out.add(v);
                    latch.countDown();
                });

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "a").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k2", "b").get(5, TimeUnit.SECONDS);
            producer.send(topic, "k3", "c").get(5, TimeUnit.SECONDS);

            assertTrue(latch.await(15, TimeUnit.SECONDS));
            assertEquals(3, out.size());
            assertTrue(out.containsAll(List.of("a=a", "b=b", "c=c")));
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void stateSchemaMismatchCanDeadLetterWhenPolicyFail() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);
        String jobName = "rt-job-" + UUID.randomUUID().toString().substring(0, 6);
        String jobInstanceId = "inst-" + UUID.randomUUID().toString().substring(0, 6);
        String stateKeyPrefix = "streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6);

        RedisRuntimeConfig cfg1 = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId(jobInstanceId)
                .stateKeyPrefix(stateKeyPrefix)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .stateSchemaEvolutionEnabled(true)
                .stateSchemaMismatchPolicy(RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL)
                .build();

        RedisRuntimeConfig cfg2 = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId(jobInstanceId)
                .stateKeyPrefix(stateKeyPrefix)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .processingErrorResult(MessageHandleResult.DEAD_LETTER)
                .stateSchemaEvolutionEnabled(true)
                .stateSchemaMismatchPolicy(RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL)
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg1.getMqOptions());
        MessageProducer producer = mq.createProducer();
        DeadLetterQueueManager dlq = new DeadLetterQueueManager(client);

        try {
            // First run writes schemaVersion=1
            RedisStreamExecutionEnvironment env1 = RedisStreamExecutionEnvironment.create(client, cfg1);
            CountDownLatch latch1 = new CountDownLatch(1);

            KeyedStream<String, String> keyed1 = env1.fromMqTopicWithId("s1", topic, group)
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> v);
            ValueState<Integer> cnt1 = keyed1.getState(new StateDescriptor<>("cnt", Integer.class, 0, 1));

            keyed1.<String>process((key, value, ctx, out) -> {
                Integer c = cnt1.value();
                if (c == null) c = 0;
                c = c + 1;
                cnt1.update(c);
                out.collect(key + ":" + c);
            }).addSink(v -> latch1.countDown());

            try (RedisJobClient job = env1.executeAsync()) {
                producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);
                assertTrue(latch1.await(15, TimeUnit.SECONDS));
            }

            // Second run bumps schemaVersion=2 and should fail fast -> DLQ
            RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(client, cfg2);
            KeyedStream<String, String> keyed2 = env2.fromMqTopicWithId("s1", topic, group)
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> v);
            ValueState<Integer> cnt2 = keyed2.getState(new StateDescriptor<>("cnt", Integer.class, 0, 2));

            keyed2.<String>process((key, value, ctx, out) -> {
                Integer c = cnt2.value();
                if (c == null) c = 0;
                cnt2.update(c + 1);
                out.collect(key + ":" + (c + 1));
            }).addSink(v -> fail("sink should not be invoked when schema mismatch is dead-lettered"));

            try (RedisJobClient job = env2.executeAsync()) {
                producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);

                boolean ok = false;
                for (int i = 0; i < 30; i++) {
                    if (dlq.getDeadLetterQueueSize(topic) >= 1) {
                        ok = true;
                        break;
                    }
                    Thread.sleep(200);
                }
                assertTrue(ok, "expected message to be written to DLQ");

                var msgs = dlq.getDeadLetterMessages(topic, 10);
                assertFalse(msgs.isEmpty());
                StreamMessageId firstId = msgs.keySet().iterator().next();
                var data = msgs.get(firstId);
                assertNotNull(data);

                String headersJson = (String) data.get("headers");
                assertNotNull(headersJson);
                ObjectMapper om = new ObjectMapper();
                var headers = om.readValue(headersJson, new TypeReference<java.util.Map<String, String>>() {
                });

                assertEquals(cfg2.getJobName(), headers.get(RedisRuntimeHeaders.JOB_NAME));
                assertEquals(group, headers.get(RedisRuntimeHeaders.CONSUMER_GROUP));
                assertEquals(IllegalStateException.class.getName(), headers.get(RedisRuntimeHeaders.ERROR_TYPE));
                assertNotNull(headers.get(RedisRuntimeHeaders.ERROR_MESSAGE));
                assertTrue(headers.get(RedisRuntimeHeaders.ERROR_MESSAGE).contains("State schema mismatch"));
            }
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void stateSchemaMismatchCanClearStateWhenPolicyClear() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);
        String jobName = "rt-job-" + UUID.randomUUID().toString().substring(0, 6);
        String jobInstanceId = "inst-" + UUID.randomUUID().toString().substring(0, 6);
        String stateKeyPrefix = "streaming:runtime:test:" + UUID.randomUUID().toString().substring(0, 6);

        RedisRuntimeConfig cfg1 = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId(jobInstanceId)
                .stateKeyPrefix(stateKeyPrefix)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .stateSchemaEvolutionEnabled(true)
                .stateSchemaMismatchPolicy(RedisRuntimeConfig.StateSchemaMismatchPolicy.FAIL)
                .build();

        RedisRuntimeConfig cfg2 = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId(jobInstanceId)
                .stateKeyPrefix(stateKeyPrefix)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .stateSchemaEvolutionEnabled(true)
                .stateSchemaMismatchPolicy(RedisRuntimeConfig.StateSchemaMismatchPolicy.CLEAR)
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg1.getMqOptions());
        MessageProducer producer = mq.createProducer();

        try {
            // First run writes schemaVersion=1 and produces state a:2
            RedisStreamExecutionEnvironment env1 = RedisStreamExecutionEnvironment.create(client, cfg1);
            CountDownLatch latch1 = new CountDownLatch(2);

            KeyedStream<String, String> keyed1 = env1.fromMqTopicWithId("s1", topic, group)
                    .map(m -> (String) m.getPayload())
                    .keyBy(v -> v);
            ValueState<Integer> cnt1 = keyed1.getState(new StateDescriptor<>("cnt", Integer.class, 0, 1));

            keyed1.<String>process((key, value, ctx, out) -> {
                Integer c = cnt1.value();
                if (c == null) c = 0;
                c = c + 1;
                cnt1.update(c);
                out.collect(key + ":" + c);
            }).addSink(v -> latch1.countDown());

            try (RedisJobClient job = env1.executeAsync()) {
                producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);
                producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);
                assertTrue(latch1.await(15, TimeUnit.SECONDS));
            }

            // Second run bumps schemaVersion=2 and clears state on mismatch
            RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(client, cfg2);
            AtomicInteger pid = new AtomicInteger(-1);
            List<String> out = new CopyOnWriteArrayList<>();
            CountDownLatch latch2 = new CountDownLatch(1);

            KeyedStream<String, String> keyed2 = env2.fromMqTopicWithId("s1", topic, group)
                    .map(m -> {
                        String p = m.getHeaders() == null ? null : m.getHeaders().get(MqHeaders.PARTITION_ID);
                        pid.set(p == null ? -1 : Integer.parseInt(p));
                        return (String) m.getPayload();
                    })
                    .keyBy(v -> v);
            ValueState<Integer> cnt2 = keyed2.getState(new StateDescriptor<>("cnt", Integer.class, 0, 2));

            keyed2.<String>process((key, value, ctx, outCollector) -> {
                Integer c = cnt2.value();
                if (c == null) c = 0;
                c = c + 1;
                cnt2.update(c);
                outCollector.collect(key + ":" + c);
            }).addSink(v -> {
                out.add(v);
                latch2.countDown();
            });

            try (RedisJobClient job = env2.executeAsync()) {
                producer.send(topic, "a", "a").get(5, TimeUnit.SECONDS);
                assertTrue(latch2.await(15, TimeUnit.SECONDS));
                assertEquals(List.of("a:1"), out, "expected state to be cleared and restart from default");
            }

            int partitionId = pid.get();
            assertTrue(partitionId >= 0);
            String operatorId = "s1-keyBy-1";
            String redisKey = cfg2.getStateKeyPrefix() + ":" + cfg2.getJobName() +
                    ":cg:" + group +
                    ":topic:" + topic +
                    ":p:" + partitionId +
                    ":state:" + operatorId + ":cnt";

            String schemaKey = cfg2.getStateKeyPrefix() + ":" + cfg2.getJobName() + ":stateSchema";
            RMap<String, String> schema = client.getMap(schemaKey, StringCodec.INSTANCE);
            assertEquals(Integer.class.getName() + "|2", schema.get(redisKey));
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void sinkDeduplicationCanPreventDuplicateSideEffectsOnRetry() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);
        String jobName = "rt-job-" + UUID.randomUUID().toString().substring(0, 6);
        String jobInstanceId = "inst-" + UUID.randomUUID().toString().substring(0, 6);
        String dedupPrefix = "streaming:runtime:test:sinkDedup:" + UUID.randomUUID().toString().substring(0, 6) + ":";

        String counterKey = "rt-test:sink-counter:" + UUID.randomUUID().toString().substring(0, 8);
        String successKey = "rt-test:sink-success:" + UUID.randomUUID().toString().substring(0, 8);
        client.getAtomicLong(counterKey).set(0);
        client.getAtomicLong(successKey).set(0);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId(jobInstanceId)
                .mqOptions(MqOptions.builder()
                        .workerThreads(1)
                        .schedulerThreads(1)
                        .consumerPollTimeoutMs(100)
                        .retryBaseBackoffMs(0)
                        .retryMaxBackoffMs(0)
                        .retryMaxAttempts(3)
                        .build())
                .sinkDeduplicationEnabled(true)
                .sinkDeduplicationTtl(Duration.ofMinutes(10))
                .sinkDedupKeyPrefix(dedupPrefix)
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();
        MessageQueueAdmin admin = mq.createAdmin();

        AtomicBoolean throwOnce = new AtomicBoolean(true);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        env.fromMqTopicWithId("s1", topic, group)
                .map(m -> (String) m.getPayload())
                .keyBy(v -> "k")
                .<String>process(new KeyedProcessFunction<String, String, String>() {
                    @Override
                    public void processElement(String key, String value, Context ctx, Collector<String> out) {
                        out.collect(value);
                        ctx.registerEventTimeTimer(ctx.currentWatermark());
                    }

                    @Override
                    public void onEventTime(long timestamp, String key, Context ctx, Collector<String> out) {
                        if (throwOnce.compareAndSet(true, false)) {
                            throw new RuntimeException("boom-after-sink");
                        }
                        client.getAtomicLong(successKey).incrementAndGet();
                    }
                })
                .addSink(v -> client.getAtomicLong(counterKey).incrementAndGet());

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "x").get(5, TimeUnit.SECONDS);

            long deadline = System.currentTimeMillis() + 15000L;
            while (System.currentTimeMillis() < deadline) {
                if (client.getAtomicLong(successKey).get() >= 1) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(1L, client.getAtomicLong(successKey).get(), "expected retry to eventually succeed");
            assertEquals(1L, client.getAtomicLong(counterKey).get(), "sink should be invoked only once due to dedup");

            long pendingDeadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < pendingDeadline) {
                if (admin.getPendingCount(topic, group) <= 0) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(0L, admin.getPendingCount(topic, group));
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void idempotentRedisSinkCanPreventDuplicateSideEffectsOnRetryWithoutRuntimeDedup() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);
        String jobName = "rt-job-" + UUID.randomUUID().toString().substring(0, 6);
        String jobInstanceId = "inst-" + UUID.randomUUID().toString().substring(0, 6);

        String tag = "rt-" + UUID.randomUUID().toString().substring(0, 6);
        String dedupSetKey = "rt-test:idem:{" + tag + "}:seen";
        String listKey = "rt-test:idem:{" + tag + "}:list";

        client.getKeys().delete(dedupSetKey, listKey);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId(jobInstanceId)
                .mqOptions(MqOptions.builder()
                        .workerThreads(1)
                        .schedulerThreads(1)
                        .consumerPollTimeoutMs(100)
                        .retryBaseBackoffMs(0)
                        .retryMaxBackoffMs(0)
                        .retryMaxAttempts(3)
                        .build())
                .sinkDeduplicationEnabled(false)
                .processingErrorResult(MessageHandleResult.RETRY)
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();
        MessageQueueAdmin admin = mq.createAdmin();

        AtomicBoolean throwOnce = new AtomicBoolean(true);
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);

        RedisIdempotentListSink<String> idemSink = new RedisIdempotentListSink<>(
                client, dedupSetKey, listKey, new ObjectMapper(), Duration.ofMinutes(10));

        env.fromMqTopicWithId("s1", topic, group)
                .map(m -> {
                    String stableId = m.getHeaders() == null ? null : m.getHeaders().get(MqHeaders.ORIGINAL_MESSAGE_ID);
                    if (stableId == null || stableId.isBlank()) {
                        stableId = m.getId();
                    }
                    return new IdempotentRecord<>(stableId, (String) m.getPayload());
                })
                .keyBy(v -> "k")
                .<IdempotentRecord<String>>process(new KeyedProcessFunction<String, IdempotentRecord<String>, IdempotentRecord<String>>() {
                    @Override
                    public void processElement(String key, IdempotentRecord<String> value, Context ctx, Collector<IdempotentRecord<String>> out) {
                        out.collect(value);
                        ctx.registerEventTimeTimer(ctx.currentWatermark());
                    }

                    @Override
                    public void onEventTime(long timestamp, String key, Context ctx, Collector<IdempotentRecord<String>> out) {
                        if (throwOnce.compareAndSet(true, false)) {
                            throw new RuntimeException("boom-after-sink");
                        }
                    }
                })
                .addSink(idemSink);

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "x").get(5, TimeUnit.SECONDS);

            long deadline = System.currentTimeMillis() + 15000L;
            while (System.currentTimeMillis() < deadline) {
                int size = client.getList(listKey, StringCodec.INSTANCE).size();
                if (size >= 1) {
                    break;
                }
                Thread.sleep(50);
            }

            assertEquals(1, client.getList(listKey, StringCodec.INSTANCE).size(),
                    "idempotent sink should write exactly once even if message is retried");

            long pendingDeadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < pendingDeadline) {
                if (admin.getPendingCount(topic, group) <= 0) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(0L, admin.getPendingCount(topic, group));
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void checkpointedRedisSinkCommitsOnlyOnCheckpointComplete() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);
        String jobName = "rt-job-" + UUID.randomUUID().toString().substring(0, 6);
        String jobInstanceId = "inst-" + UUID.randomUUID().toString().substring(0, 6);
        String checkpointKeyPrefix = "streaming:runtime:checkpoint:test:" + UUID.randomUUID().toString().substring(0, 6) + ":";

        String tag = "rt-" + UUID.randomUUID().toString().substring(0, 6);
        String dedupSetKey = "rt-test:cp-commit:{" + tag + "}:seen";
        String listKey = "rt-test:cp-commit:{" + tag + "}:list";
        client.getKeys().delete(dedupSetKey, listKey);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .jobInstanceId(jobInstanceId)
                .checkpointKeyPrefix(checkpointKeyPrefix)
                .checkpointInterval(Duration.ZERO) // manual
                .restoreFromLatestCheckpoint(false)
                .deferAckUntilCheckpoint(true)
                .mqOptions(MqOptions.builder()
                        .workerThreads(1)
                        .schedulerThreads(1)
                        .consumerPollTimeoutMs(100)
                        .build())
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();
        MessageQueueAdmin admin = mq.createAdmin();

        CountDownLatch processed = new CountDownLatch(1);

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
        env.fromMqTopicWithId("s1", topic, group)
                .map(m -> {
                    String stableId = m.getHeaders() == null ? null : m.getHeaders().get(MqHeaders.ORIGINAL_MESSAGE_ID);
                    if (stableId == null || stableId.isBlank()) {
                        stableId = m.getId();
                    }
                    processed.countDown();
                    return new IdempotentRecord<>(stableId, (String) m.getPayload());
                })
                .addSink(new RedisCheckpointedIdempotentListSink<>(client, dedupSetKey, listKey, Duration.ofMinutes(10)));

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "x").get(5, TimeUnit.SECONDS);
            assertTrue(processed.await(15, TimeUnit.SECONDS));

            assertEquals(0, client.getList(listKey, StringCodec.INSTANCE).size(),
                    "checkpointed sink should not commit side effects before checkpoint");
            assertTrue(admin.getPendingCount(topic, group) >= 1, "expected pending when ack is deferred");

            Checkpoint cp = job.triggerCheckpointNow();
            assertNotNull(cp);

            long deadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < deadline) {
                if (client.getList(listKey, StringCodec.INSTANCE).size() >= 1) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(1, client.getList(listKey, StringCodec.INSTANCE).size(),
                    "checkpointed sink should commit side effects on checkpoint complete");

            long pendingDeadline = System.currentTimeMillis() + 5000L;
            while (System.currentTimeMillis() < pendingDeadline) {
                if (admin.getPendingCount(topic, group) <= 0) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals(0L, admin.getPendingCount(topic, group), "expected pending cleared after checkpoint ack");
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    @Test
    void processingErrorCanDeadLetter() throws Exception {
        RedissonClient client = createClient();
        String topic = "rt-topic-" + UUID.randomUUID().toString().substring(0, 8);
        String group = "rt-group-" + UUID.randomUUID().toString().substring(0, 8);

        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName("rt-job-" + UUID.randomUUID().toString().substring(0, 6))
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .processingErrorResult(MessageHandleResult.DEAD_LETTER)
                .build();

        MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
        MessageProducer producer = mq.createProducer();

        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
        env.fromMqTopic(topic, group)
                .map(m -> {
                    throw new RuntimeException("boom");
                })
                .addSink(v -> fail("sink should not be invoked when processing errors are dead-lettered"));

        DeadLetterQueueManager dlq = new DeadLetterQueueManager(client);

        try (RedisJobClient job = env.executeAsync()) {
            producer.send(topic, "k1", "x").get(5, TimeUnit.SECONDS);

            boolean ok = false;
            for (int i = 0; i < 30; i++) {
                if (dlq.getDeadLetterQueueSize(topic) >= 1) {
                    ok = true;
                    break;
                }
                Thread.sleep(200);
            }
            assertTrue(ok, "expected message to be written to DLQ");

            var msgs = dlq.getDeadLetterMessages(topic, 10);
            assertFalse(msgs.isEmpty());
            StreamMessageId firstId = msgs.keySet().iterator().next();
            var data = msgs.get(firstId);
            assertNotNull(data);

            String headersJson = (String) data.get("headers");
            assertNotNull(headersJson);
            ObjectMapper om = new ObjectMapper();
            var headers = om.readValue(headersJson, new TypeReference<java.util.Map<String, String>>() {
            });

            assertEquals(cfg.getJobName(), headers.get(RedisRuntimeHeaders.JOB_NAME));
            assertEquals(group, headers.get(RedisRuntimeHeaders.CONSUMER_GROUP));
            assertEquals(RuntimeException.class.getName(), headers.get(RedisRuntimeHeaders.ERROR_TYPE));
            assertEquals("boom", headers.get(RedisRuntimeHeaders.ERROR_MESSAGE));
        } finally {
            producer.close();
            client.shutdown();
        }
    }

    private static RedissonClient createClient() {
        String url = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(url);
        return Redisson.create(cfg);
    }
}
