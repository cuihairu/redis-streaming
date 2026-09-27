package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisRuntimeCheckpointManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RT-H2 regression: the checkpoint retention sweep used to run inside the stop-the-world
 * window — {@code triggerCheckpoint} swept while the consumers were still paused, so the
 * pause on every tick stretched by the full cost of listing and deserializing every
 * retained checkpoint (each carrying a complete state snapshot). The sweep now runs after
 * the {@code finally} block has resumed the consumers.
 */
class RedisStreamExecutionEnvironmentCleanupOffPauseTest {

    private RedissonClient redisson;
    private MessageQueueFactory mqFactory;
    private final List<RecordingConsumer> consumers = new ArrayList<>();
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final SweepRecordingStorage storage = new SweepRecordingStorage(events);

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        RMap<Object, Object> map = mock(RMap.class);
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        when(redisson.getMap(anyString())).thenReturn((RMap) map);
        when(redisson.getMap(anyString(), any(Codec.class))).thenReturn((RMap) map);
        when(script.eval(any(), anyString(), any(), anyList(), any(Object[].class))).thenReturn("OK");
        RBucket<Object> bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn((RBucket) bucket);
        mqFactory = mock(MessageQueueFactory.class);
        when(mqFactory.createConsumer(anyString())).thenAnswer(inv -> {
            RecordingConsumer c = new RecordingConsumer(events);
            consumers.add(c);
            return c;
        });
    }

    @AfterEach
    void tearDown() {
        events.clear();
    }

    private RedisRuntimeConfig baseConfig() {
        return RedisRuntimeConfig.builder()
                .jobName("sweep-" + UUID.randomUUID().toString().substring(0, 6))
                .stateKeyPrefix("sweep-state:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointKeyPrefix("sweep-cp:" + UUID.randomUUID().toString().substring(0, 6))
                .checkpointsToKeep(2)
                .checkpointDrainTimeout(Duration.ZERO)
                .restoreConsumerGroupFromCommitFrontier(false)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
    }

    private RedisJobClient launch(RedisRuntimeConfig cfg) throws Exception {
        RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.createForTesting(
                redisson, cfg, mqFactory, new ObjectMapper());
        env.fromMqTopic("t", "g").addSink(v -> {
        });
        RedisJobClient job = env.executeAsync();
        // swap in the recording storage only now: startup restore legitimately touches the
        // real storage against the mocked Redisson and must not pollute the event log
        Field f = job.getClass().getDeclaredField("checkpointManager");
        f.setAccessible(true);
        RedisRuntimeCheckpointManager manager = (RedisRuntimeCheckpointManager) f.get(job);
        Field sf = RedisRuntimeCheckpointManager.class.getDeclaredField("storage");
        sf.setAccessible(true);
        sf.set(manager, storage);
        return job;
    }

    @Test
    void retentionSweepRunsAfterConsumersAreResumed() throws Exception {
        RedisJobClient job = launch(baseConfig());
        try {
            assertNotNull(job.triggerCheckpointNow());

            assertTrue(events.contains("pause"), "the checkpoint must pause the consumers");
            assertTrue(events.contains("resume"), "the checkpoint must resume the consumers");
            assertTrue(events.contains("listCheckpoints"),
                    "the retention sweep must still run after the checkpoint");
            assertTrue(events.indexOf("listCheckpoints") > events.lastIndexOf("resume"),
                    "RT-H2: the retention sweep ran while the consumers were still paused "
                            + "(events=" + events + ")");
        } finally {
            job.cancel();
        }
    }

    @Test
    void sweepStillTrimsToCheckpointsToKeepAfterResume() throws Exception {
        RedisJobClient job = launch(baseConfig());
        try {
            assertNotNull(job.triggerCheckpointNow());
            assertNotNull(job.triggerCheckpointNow());
            assertNotNull(job.triggerCheckpointNow());

            assertEquals(2, storage.stored.size(),
                    "the deferred sweep must still evict beyond checkpointsToKeep");
            assertEquals(Set.of(2L, 3L), storage.stored.keySet(),
                    "the oldest checkpoint is evicted first, exactly as before");

            int firstResume = events.indexOf("resume");
            int firstSweep = events.indexOf("listCheckpoints");
            assertTrue(firstSweep > firstResume,
                    "no sweep may precede the first resume (events=" + events + ")");
        } finally {
            job.cancel();
        }
    }

    private static final class RecordingConsumer implements MessageConsumer, PausableMessageConsumer {

        private final List<String> events;

        private RecordingConsumer(List<String> events) {
            this.events = events;
        }

        @Override
        public void subscribe(String topic, MessageHandler handler) {
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler, SubscriptionOptions options) {
        }

        @Override
        public void unsubscribe(String topic) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void pause() {
            events.add("pause");
        }

        @Override
        public void resume() {
            events.add("resume");
        }

        @Override
        public long inFlight() {
            return 0;
        }

        @Override
        public boolean isPaused() {
            return false;
        }
    }

    /**
     * In-memory storage that records the sweep-relevant operations in the shared event log,
     * so tests can assert in which phase of the checkpoint they ran.
     */
    private static final class SweepRecordingStorage extends RedisCheckpointStorage {

        private final java.util.Map<Long, Checkpoint> stored = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<String> events;

        private SweepRecordingStorage(List<String> events) {
            super(null, "sweep-test:");
            this.events = events;
        }

        private List<Checkpoint> newestFirst() {
            return stored.values().stream()
                    // timestamp desc, then id desc so same-millisecond checkpoints keep a
                    // deterministic newest-first order
                    .sorted(Comparator.comparingLong(Checkpoint::getTimestamp)
                            .thenComparingLong(Checkpoint::getCheckpointId)
                            .reversed())
                    .collect(Collectors.toList());
        }

        @Override
        public void storeCheckpoint(Checkpoint checkpoint) {
            events.add("store");
            stored.put(checkpoint.getCheckpointId(), checkpoint);
        }

        @Override
        public Checkpoint loadCheckpoint(long checkpointId) {
            return stored.get(checkpointId);
        }

        @Override
        public Checkpoint getLatestCheckpoint() {
            return newestFirst().stream()
                    .filter(Checkpoint::isCompleted)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<Checkpoint> listCheckpoints(int limit) {
            events.add("listCheckpoints");
            return newestFirst().stream().limit(limit).collect(Collectors.toList());
        }

        @Override
        public boolean deleteCheckpoint(long checkpointId) {
            events.add("delete");
            return stored.remove(checkpointId) != null;
        }

        @Override
        public int cleanupOldCheckpoints(int keepCount) {
            List<Checkpoint> all = listCheckpoints(Integer.MAX_VALUE);
            int deleted = 0;
            for (int i = keepCount; i < all.size(); i++) {
                if (deleteCheckpoint(all.get(i).getCheckpointId())) {
                    deleted++;
                }
            }
            return deleted;
        }

        @Override
        public void close() {
        }
    }
}
