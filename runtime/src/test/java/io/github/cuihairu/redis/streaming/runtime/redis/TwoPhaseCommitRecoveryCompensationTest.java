package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.TwoPhaseCommitSink;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
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
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.Codec;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Environment-level two-phase-commit recovery compensation. The restore path must replay the
 * transaction handles of a checkpoint that was stored but never sink-committed (a crash between
 * the checkpoint store and the sink's phase 2) and must skip the handles of a checkpoint whose
 * sink-committed marker is present.
 *
 * <p>The {@code redis.internal} tests cover the coordinator and runner halves of the protocol;
 * what only the environment can pin is the wiring in between: reading the handles out of the
 * restored checkpoint, gating on the {@code runtime:sinkCommitted:<id>} marker, and attributing
 * each handle to the runner that owns it through the flat {@code runnerIndex:sinkIndex} key.
 * Driven on mocked Redis/MQ collaborators with a real {@link DefaultCheckpoint} in the checkpoint
 * bucket, so no Redis is required.</p>
 *
 * <p>With defer-ack off the restore serves the latest stored checkpoint, which is the simplest
 * way to reach a stored-but-uncommitted one. With defer-ack on the runtime additionally adopts
 * the newest in-doubt epoch over the older sink-committed checkpoint — the offsets and the
 * transaction handles were captured together, so finalizing the staged data instead of
 * re-processing the records is both cheaper and the exactly-once behaviour. The last test pins
 * that preference.</p>
 */
class TwoPhaseCommitRecoveryCompensationTest {

    private static final String SINK_COMMITTED_MARKER_PREFIX = "runtime:sinkCommitted:";

    private RedissonClient redisson;
    private RKeys rkeys;
    private MessageQueueFactory mqFactory;
    private final List<TestConsumer> consumers = new ArrayList<>();
    private final List<String> checkpointKeys = new ArrayList<>();
    private final Map<String, RBucket<Object>> buckets = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        redisson = mock(RedissonClient.class);
        rkeys = mock(RKeys.class);
        RScript script = mock(RScript.class);
        RStream<Object, Object> stream = mock(RStream.class);
        when(redisson.getKeys()).thenReturn(rkeys);
        when(redisson.getScript(any(Codec.class))).thenReturn(script);
        when(redisson.getSet(anyString(), any(Codec.class))).thenReturn((RSet) mock(RSet.class));
        when(redisson.getMap(anyString())).thenAnswer(inv -> (RMap) mock(RMap.class));
        when(redisson.getStream(anyString(), any(Codec.class))).thenReturn(stream);
        when(script.eval(any(), anyString(), any(), anyList(), any(), any())).thenReturn("OK");
        when(redisson.getBucket(anyString()))
                .thenAnswer(inv -> bucketFor(inv.getArgument(0)));
        when(redisson.getBucket(anyString(), any(Codec.class)))
                .thenAnswer(inv -> bucketFor(inv.getArgument(0)));
        when(rkeys.getKeys(any(KeysScanOptions.class)))
                .thenAnswer(inv -> List.copyOf(checkpointKeys));

        mqFactory = mock(MessageQueueFactory.class);
        when(mqFactory.createConsumer(anyString())).thenAnswer(inv -> {
            TestConsumer c = new TestConsumer();
            consumers.add(c);
            return c;
        });
    }

    @AfterEach
    void tearDown() {
        consumers.forEach(TestConsumer::release);
    }

    @Test
    void storedHandlesOfAnUnmarkedCheckpointAreReplayedThroughRecoverAndCommit() throws Exception {
        long checkpointId = 42L;
        RedisRuntimeConfig cfg = config("tpc-replay");
        // "0:0" belongs to the single runner of this job, "1:0" to a runner this parallelism does
        // not have: only the handle the runner owns may be replayed
        storeCheckpoint(cfg, checkpointId, 1L,
                Map.of("0:0", encode("epoch-7"), "1:0", encode("other-runner")));

        RecordingTwoPhaseSink sink = new RecordingTwoPhaseSink();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").map(m -> "x").addSink(sink);
        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(42L, job.diagnostics().get("restoredCheckpointId"),
                    "the uncommitted checkpoint must be the restore point, otherwise the following "
                            + "assertion would pass without any replay having been attempted");
            assertEquals(List.of("recovered:epoch-7"), sink.recovered,
                    "a stored-but-uncommitted checkpoint must have its handle replayed through the "
                            + "recovery compensation, decoded back into the original transaction");
            assertEquals(List.of(), sink.discarded,
                    "a crash after the store is compensated by committing, never by discarding");
        } finally {
            job.cancel();
        }
    }

    @Test
    void handlesOfASinkCommittedCheckpointAreNotReplayed() throws Exception {
        long checkpointId = 43L;
        RedisRuntimeConfig cfg = config("tpc-skip");
        storeCheckpoint(cfg, checkpointId, 1L, Map.of("0:0", encode("epoch-9")));
        // the marker write landed before the crash, so phase 2 already made the data visible
        when(bucketFor(markerKey(cfg, checkpointId)).isExists()).thenReturn(true);

        RecordingTwoPhaseSink sink = new RecordingTwoPhaseSink();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").map(m -> "x").addSink(sink);
        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(43L, job.diagnostics().get("restoredCheckpointId"),
                    "the checkpoint must still be restored — only the replay has to be skipped, so "
                            + "this is the control for the replay assertion of the test above");
            assertEquals(List.of(), sink.recovered,
                    "an already sink-committed checkpoint must not be replayed: its data is visible");
        } finally {
            job.cancel();
        }
    }

    @Test
    void aRestoredCheckpointWithoutHandlesLeavesTheSinkUntouched() {
        RedisRuntimeConfig cfg = config("tpc-empty");
        storeCheckpoint(cfg, 44L, 1L, Map.of());

        RecordingTwoPhaseSink sink = new RecordingTwoPhaseSink();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").map(m -> "x").addSink(sink);
        RedisJobClient job = env.executeAsync();
        try {
            assertNotNull(job);
            assertEquals(44L, job.diagnostics().get("restoredCheckpointId"));
            assertEquals(List.of(), sink.recovered);
            assertEquals(List.of(), sink.discarded);
            assertEquals(0, sink.prepared, "recovery must not synthesize a transaction");
        } finally {
            job.cancel();
        }
    }

    @Test
    void deferAckAdoptsTheInDoubtEpochOverTheOlderCommittedCheckpoint() throws Exception {
        long committedId = 50L;
        long inDoubtId = 51L;
        RedisRuntimeConfig cfg = deferAckConfig("tpc-in-doubt");
        storeCheckpoint(cfg, committedId, 1L, Map.of());
        storeCheckpoint(cfg, inDoubtId, 2L, Map.of("0:0", encode("epoch-11")));
        // the older checkpoint finished phase 2; the newer one died between store and commit
        when(bucketFor(markerKey(cfg, committedId)).isExists()).thenReturn(true);

        RecordingTwoPhaseSink sink = new RecordingTwoPhaseSink();
        RedisStreamExecutionEnvironment env = environment(cfg);
        env.fromMqTopic("t", "g").map(m -> "x").addSink(sink);
        RedisJobClient job = env.executeAsync();
        try {
            assertEquals(inDoubtId, job.diagnostics().get("restoredCheckpointId"),
                    "the in-doubt epoch is the newer restore point: its offsets and its handles "
                            + "were captured together, so the staged data gets finalized instead "
                            + "of the records being processed a second time");
            assertEquals(List.of("recovered:epoch-11"), sink.recovered);
        } finally {
            job.cancel();
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Puts a completed checkpoint carrying {@code handles} where the storage looks for it: the
     * storage scans {@code keyPrefix + "*"} and accepts only a pure-numeric suffix, so the key
     * ends in the checkpoint id.
     */
    private void storeCheckpoint(RedisRuntimeConfig cfg, long checkpointId, long timestamp,
                                 Map<String, String> handles) {
        DefaultCheckpoint cp = new DefaultCheckpoint(checkpointId, timestamp);
        cp.getStateSnapshot().putState(RedisRuntimeCheckpointManager.SNAPSHOT_KEY_TXNS, handles);
        cp.markCompleted();

        String key = checkpointKeyPrefix(cfg) + checkpointId;
        checkpointKeys.add(key);
        when(bucketFor(key).get()).thenReturn(cp);
    }

    private static String checkpointKeyPrefix(RedisRuntimeConfig cfg) {
        return cfg.getCheckpointKeyPrefix() + cfg.getJobName() + ":";
    }

    private static String markerKey(RedisRuntimeConfig cfg, long checkpointId) {
        return checkpointKeyPrefix(cfg) + SINK_COMMITTED_MARKER_PREFIX + checkpointId;
    }

    @SuppressWarnings("unchecked")
    private RBucket<Object> bucketFor(String key) {
        return buckets.computeIfAbsent(key, k -> (RBucket<Object>) mock(RBucket.class));
    }

    private RedisRuntimeConfig config(String name) {
        String suffix = name + "-" + UUID.randomUUID().toString().substring(0, 6);
        return RedisRuntimeConfig.builder()
                .jobName(suffix)
                .stateKeyPrefix("tpc-env:" + suffix + ":state:")
                .checkpointKeyPrefix("tpc-env:" + suffix + ":cp:")
                .restoreFromLatestCheckpoint(true)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
    }

    private RedisRuntimeConfig deferAckConfig(String name) {
        String suffix = name + "-" + UUID.randomUUID().toString().substring(0, 6);
        return RedisRuntimeConfig.builder()
                .jobName(suffix)
                .stateKeyPrefix("tpc-env:" + suffix + ":state:")
                .checkpointKeyPrefix("tpc-env:" + suffix + ":cp:")
                .restoreFromLatestCheckpoint(true)
                .deferAckUntilCheckpoint(true)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).build())
                .build();
    }

    private RedisStreamExecutionEnvironment environment(RedisRuntimeConfig cfg) {
        return RedisStreamExecutionEnvironment.createForTesting(redisson, cfg, mqFactory, new ObjectMapper());
    }

    /**
     * The handle encoding the runtime stores — Java serialization + Base64. Pinned here on purpose
     * so a change of that wire format breaks this test rather than only a Redis-backed restart.
     */
    private static String encode(String txn) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(txn);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static final class RecordingTwoPhaseSink implements TwoPhaseCommitSink<String, String> {
        final List<String> recovered = new ArrayList<>();
        final List<String> discarded = new ArrayList<>();
        int prepared;

        @Override
        public void invoke(String value) {
            throw new UnsupportedOperationException(
                    "the runtime routes two-phase sinks through invoke(value, txn)");
        }

        @Override
        public String beginTxn() {
            return "live-epoch";
        }

        @Override
        public void invoke(String value, String txn) {
        }

        @Override
        public void preCommit(String txn) {
            prepared++;
        }

        @Override
        public void commit(String txn) {
        }

        @Override
        public String recoverAndCommit(String txn) {
            recovered.add("recovered:" + txn);
            return txn;
        }

        @Override
        public String recoverAndAbort(String txn) {
            discarded.add("discarded:" + txn);
            return txn;
        }
    }

    private static final class TestConsumer implements MessageConsumer, PausableMessageConsumer {
        volatile MessageHandler handler;

        @Override
        public void subscribe(String topic, MessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void subscribe(String topic, String consumerGroup, MessageHandler handler, SubscriptionOptions options) {
            this.handler = handler;
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
        }

        @Override
        public void resume() {
        }

        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public long inFlight() {
            return 0L;
        }

        void release() {
        }
    }
}
