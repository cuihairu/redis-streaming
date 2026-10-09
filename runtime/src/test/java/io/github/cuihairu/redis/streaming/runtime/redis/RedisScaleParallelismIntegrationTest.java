package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.state.StateDescriptor;
import io.github.cuihairu.redis.streaming.api.state.ValueState;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P4 dynamic scaling: {@code RedisJobClient.scaleParallelism} re-pins the live
 * consumers to {@code partitionId % newParallelism == subtaskIndex} and adds/removes
 * subtasks mid-flight. Keyed state and checkpoints are partition-keyed (never
 * subtask-keyed), so counts survive both a live resize and a stop/restart at a
 * different parallelism.
 */
@Tag("integration")
class RedisScaleParallelismIntegrationTest {

    private static final List<String> KEYS = List.of("ka", "kb", "kc", "kd", "ke");

    private static RedissonClient createClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(config);
    }

    private static MqOptions mqOptions() {
        return MqOptions.builder().workerThreads(4).schedulerThreads(2)
                .defaultPartitionCount(4)
                // the lease cap defaults to workerThreads; make all 4 partitions leasable
                .maxLeasedPartitionsPerConsumer(4)
                .rebalanceIntervalSec(1).renewIntervalSec(1)
                .consumerPollTimeoutMs(150).build();
    }

    private static boolean waitUntil(java.util.concurrent.Callable<Boolean> cond, long timeoutMs) throws Exception {
        long dl = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < dl) {
            if (Boolean.TRUE.equals(cond.call())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /**
     * Scale 1 -> 3 -> 2 mid-flight: every partition keeps being served (no stranding)
     * and the keyed count state stays exact across repins — a partition's state moves
     * with the partition, because it is keyed by partition, not by subtask.
     */
    @Test
    @SuppressWarnings("unchecked")
    void resizesMidFlightWithoutStrandingPartitionsOrLosingCounts() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 6);
        String topic = "rt-scale-" + uid;
        String group = "rt-scale-grp-" + uid;
        int perWave = 6;
        try {
            RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                    .jobName("rt-scale-" + uid)
                    .stateKeyPrefix("streaming:runtime:scaletest:" + uid)
                    .mqOptions(mqOptions())
                    .pipelineParallelism(1)
                    .build();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
            Map<String, Integer> counts = new ConcurrentHashMap<>();
            StateDescriptor<Integer> desc = new StateDescriptor<>("cnt", Integer.class, 0);
            DataStream<String> base = env.fromMqTopic(topic, group).map(m -> (String) m.getPayload());
            KeyedStream<String, String> keyed = base.keyBy(v -> v);
            ValueState<Integer> cnt = keyed.getState(desc);
            keyed.<String>process((key, value, ctx, out) -> {
                        int c = (cnt.value() == null ? 0 : cnt.value()) + 1;
                        cnt.update(c);
                        counts.put(key, c);
                    })
                    .addSink(v -> { });

            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                try {
                    // payload IS the state key (keyBy payload): stable keys accumulate across waves
                    for (int i = 0; i < perWave; i++) {
                        for (String k : KEYS) {
                            producer.send(topic, k, k).get(5, TimeUnit.SECONDS);
                        }
                    }
                    assertTrue(waitUntil(() -> allKeysAtLeast(counts, perWave), 20_000),
                            "wave 1 fully counted, counts=" + counts);

                    assertTrue(job.scaleParallelism(3), "scale up must apply");
                    assertEquals(3, ((Map<String, Object>) job.diagnostics()).get("liveParallelism"));

                    for (int i = 0; i < perWave; i++) {
                        for (String k : KEYS) {
                            producer.send(topic, k, k).get(5, TimeUnit.SECONDS);
                        }
                    }
                    assertTrue(waitUntil(() -> allKeysAtLeast(counts, 2 * perWave), 20_000),
                            "wave 2 fully counted at parallelism 3, counts=" + counts);

                    assertTrue(job.scaleParallelism(2), "scale down must apply");
                    assertEquals(2, ((Map<String, Object>) job.diagnostics()).get("liveParallelism"));

                    for (int i = 0; i < perWave; i++) {
                        for (String k : KEYS) {
                            producer.send(topic, k, k).get(5, TimeUnit.SECONDS);
                        }
                    }
                    assertTrue(waitUntil(() -> allKeysAtLeast(counts, 3 * perWave), 25_000),
                            "wave 3 fully counted after scale down, counts=" + counts);

                    // exactness: state updates must be neither lost nor doubled by the handovers
                    for (String k : KEYS) {
                        assertEquals(3 * perWave, counts.get(k), "exact count for key " + k);
                    }
                } finally {
                    producer.close();
                }
            }
        } finally {
            client.shutdown();
        }
    }

    private static boolean allKeysAtLeast(Map<String, Integer> counts, int target) {
        return KEYS.stream().allMatch(k -> counts.getOrDefault(k, 0) >= target);
    }

    /**
     * Checkpoint forward compatibility: a checkpoint written at parallelism 2 restores
     * at parallelism 3 and the keyed counters continue from the restored values (state
     * keys carry the partition, never the subtask count). A fresh (non-restored) start
     * would count only the second wave (3 per key) — continuing at 8 proves the restore.
     */
    @Test
    @SuppressWarnings("unchecked")
    void checkpointAtParallelismTwoRestoresAtThree() throws Exception {
        RedissonClient client = createClient();
        String uid = UUID.randomUUID().toString().substring(0, 6);
        String topic = "rt-fwd-" + uid;
        String group = "rt-fwd-grp-" + uid;
        String prefix = "streaming:runtime:fwdtest:" + uid;
        String jobName = "rt-fwd-" + uid;
        try {
            int firstMin = runCountingWave(client, topic, group, prefix, jobName, 2, "f1", 5);
            assertEquals(5, firstMin, "wave 1 counted at parallelism 2");

            int secondMin = runCountingWave(client, topic, group, prefix, jobName, 3, "f2", 3);
            assertTrue(secondMin >= 8,
                    "job restored at parallelism 3 must continue from the checkpointed counts (>=8 per key), got " + secondMin);
        } finally {
            client.shutdown();
        }
    }

    /** Runs one counting-job lifecycle (restore enabled); returns the smallest final count across keys. */
    private static int runCountingWave(RedissonClient client, String topic, String group, String prefix,
                                       String jobName, int parallelism, String wave, int perKey) throws Exception {
        RedisRuntimeConfig cfg = RedisRuntimeConfig.builder()
                .jobName(jobName)
                .stateKeyPrefix(prefix)
                .mqOptions(mqOptions())
                .pipelineParallelism(parallelism)
                .restoreFromLatestCheckpoint(true)
                .build();
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(client, cfg);
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        StateDescriptor<Integer> desc = new StateDescriptor<>("cnt", Integer.class, 0);
        KeyedStream<String, String> keyed = env.fromMqTopic(topic, group)
                .map(m -> (String) m.getPayload())
                .keyBy(v -> v);
        ValueState<Integer> cnt = keyed.getState(desc);
        keyed.<String>process((key, value, ctx, out) -> {
                    int c = (cnt.value() == null ? 0 : cnt.value()) + 1;
                    cnt.update(c);
                    counts.put(key, c);
                })
                .addSink(v -> { });

        int waveBase = wave.equals("f1") ? 0 : 5; // the second run continues from the restored state
        try (RedisJobClient job = env.executeAsync()) {
            MessageQueueFactory mq = new MessageQueueFactory(client, cfg.getMqOptions());
            MessageProducer producer = mq.createProducer();
            try {
                for (int i = 0; i < perKey; i++) {
                    for (int k = 1; k <= 5; k++) {
                        // payload IS the state key (keyBy payload): "key1".."key5" accumulate
                        producer.send(topic, "key" + k, "key" + k).get(5, TimeUnit.SECONDS);
                    }
                }
                int target = waveBase + perKey;
                assertTrue(waitUntil(() -> counts.values().stream().allMatch(v -> v >= target), 25_000),
                        "wave " + wave + " fully counted (target " + target + "), counts=" + counts);
                job.triggerCheckpointNow();
                // give the checkpoint store a beat to land before cancel
                Thread.sleep(300);
            } finally {
                producer.close();
            }
        }
        return counts.values().stream().min(Integer::compare).orElse(0);
    }
}
