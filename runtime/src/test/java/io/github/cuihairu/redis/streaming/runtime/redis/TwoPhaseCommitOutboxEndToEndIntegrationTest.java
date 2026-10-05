package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.api.stream.IdempotentRecord;
import io.github.cuihairu.redis.streaming.api.stream.TwoPhaseCommitSink;
import io.github.cuihairu.redis.streaming.mq.MessageProducer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.runtime.redis.sink.RedisOutboxDispatcher;
import io.github.cuihairu.redis.streaming.runtime.redis.sink.RedisOutboxSink;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.config.Config;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end two-phase-commit verification on real Redis: the outbox sink driven by the
 * runtime checkpoint flow, plus the two fault windows the unit suites simulate with mocks —
 * a crash between checkpoint store and sink commit (recovered by replaying the stored
 * handles on restore) and a preCommit failure (epoch aborted, deferred-ack redelivery
 * republishes the records into the next epoch).
 */
@Tag("integration")
class TwoPhaseCommitOutboxEndToEndIntegrationTest {

    private static RedissonClient client() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress(System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379"));
        return Redisson.create(cfg);
    }

    /**
     * Delegates everything to a real {@link RedisOutboxSink} and adds the injection points
     * the scenarios need: one-shot preCommit/commit failures and an invoke counter the tests
     * await on (records are only buffered in sink memory until a checkpoint flushes them).
     */
    static final class FaultyOutboxSink
            implements TwoPhaseCommitSink<IdempotentRecord<String>, RedisOutboxSink.OutboxTxn> {

        private final RedisOutboxSink<String> delegate;
        final AtomicBoolean failPreCommitOnce = new AtomicBoolean();
        final AtomicBoolean failCommitOnce = new AtomicBoolean();
        final AtomicInteger invoked = new AtomicInteger();
        final AtomicReference<String> lastEpoch = new AtomicReference<>();

        FaultyOutboxSink(RedisOutboxSink<String> delegate) {
            this.delegate = delegate;
        }

        @Override
        public RedisOutboxSink.OutboxTxn beginTxn() throws Exception {
            return delegate.beginTxn();
        }

        @Override
        public void invoke(IdempotentRecord<String> value, RedisOutboxSink.OutboxTxn txn) throws Exception {
            invoked.incrementAndGet();
            lastEpoch.set(txn.epoch());
            delegate.invoke(value, txn);
        }

        @Override
        public void preCommit(RedisOutboxSink.OutboxTxn txn) throws Exception {
            if (failPreCommitOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("injected preCommit failure");
            }
            delegate.preCommit(txn);
        }

        @Override
        public void commit(RedisOutboxSink.OutboxTxn txn) throws Exception {
            if (failCommitOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("injected commit failure (crash between store and commit)");
            }
            delegate.commit(txn);
        }

        @Override
        public RedisOutboxSink.OutboxTxn recoverAndCommit(RedisOutboxSink.OutboxTxn txn) {
            return delegate.recoverAndCommit(txn);
        }

        @Override
        public RedisOutboxSink.OutboxTxn recoverAndAbort(RedisOutboxSink.OutboxTxn txn) {
            return delegate.recoverAndAbort(txn);
        }
    }

    private static boolean await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return cond.getAsBoolean();
    }

    private static String epochStatus(RedissonClient redis, String outboxKey, String epoch) {
        if (epoch == null) {
            return null;
        }
        RMap<String, String> epochs = redis.getMap(outboxKey + ":epochs", StringCodec.INSTANCE);
        return epochs.get(epoch);
    }

    private static long dlqCount(RedissonClient redis, String outboxKey) {
        try {
            RStream<String, String> dlq = redis.getStream(outboxKey + ":dlq", StringCodec.INSTANCE);
            return dlq.range(100, StreamMessageId.MIN, StreamMessageId.MAX).size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static RedisRuntimeConfig.Builder baseConfig(String jobName, String statePrefix) {
        return RedisRuntimeConfig.builder()
                .jobName(jobName)
                .stateKeyPrefix(statePrefix)
                .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).consumerPollTimeoutMs(200).build());
    }

    @Test
    void outboxCommitsOnCheckpointAndDispatcherDeliversOnce() throws Exception {
        String statePrefix = "streaming:2pce:" + UUID.randomUUID().toString().substring(0, 8);
        String outboxKey = "streaming:2pce-outbox:" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "2pce-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            RedisRuntimeConfig cfg = baseConfig("2pce-ok-" + UUID.randomUUID().toString().substring(0, 6), statePrefix).build();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            FaultyOutboxSink sink = new FaultyOutboxSink(new RedisOutboxSink<>(redis, outboxKey));
            env.fromMqTopic(topic, "g")
                    .map(m -> new IdempotentRecord<>("id-" + m.getPayload(), (String) m.getPayload()))
                    .addSink(sink);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                for (int i = 0; i < 4; i++) {
                    producer.send(topic, "k" + i, "p" + i).get(5, TimeUnit.SECONDS);
                }
                // records sit in the sink's in-memory epoch until the checkpoint flushes them
                assertTrue(await(() -> sink.invoked.get() >= 4, 15_000),
                        "all records should reach the sink, invoked=" + sink.invoked.get());

                Checkpoint cp = job.triggerCheckpointNow();
                assertNotNull(cp, "manual checkpoint should store and commit");
                assertEquals(RedisOutboxSink.STATUS_COMMITTED, epochStatus(redis, outboxKey, sink.lastEpoch.get()),
                        "commit must flip the epoch marker right after the checkpoint stored");
                assertEquals(4, redis.getStream(outboxKey, StringCodec.INSTANCE).size(),
                        "preCommit must have flushed exactly the epoch's records into the outbox");

                Set<String> ids = new HashSet<>();
                List<Long> seqs = new CopyOnWriteArrayList<>();
                RedisOutboxDispatcher<String> dispatcher = new RedisOutboxDispatcher<>(redis, outboxKey, String.class,
                        d -> {
                            ids.add(d.id());
                            seqs.add(d.seq());
                        },
                        "outbox-e2e", "relay-1", 100, 5, 1_000L, 100L, outboxKey + ":dlq");
                dispatcher.start();
                try {
                    assertTrue(await(() -> ids.size() >= 4, 20_000),
                            "dispatcher should deliver every committed record, got=" + ids);
                    assertEquals(Set.of("id-p0", "id-p1", "id-p2", "id-p3"), ids);
                    assertEquals(4, seqs.size());
                    for (int i = 0; i < seqs.size(); i++) {
                        assertEquals(i, seqs.get(i), "delivery must follow per-epoch sequence order");
                    }
                    assertEquals(0, dlqCount(redis, outboxKey), "nothing may overflow to the DLQ on the happy path");
                } finally {
                    dispatcher.close();
                }
                producer.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.getKeys().deleteByPattern(statePrefix + "*");
            redis.getKeys().deleteByPattern(outboxKey + "*");
            redis.shutdown();
        }
    }

    @Test
    void restoreReplaysStoredHandlesWhenCommitCrashedAfterCheckpointStore() throws Exception {
        String statePrefix = "streaming:2pcc:" + UUID.randomUUID().toString().substring(0, 8);
        String outboxKey = "streaming:2pcc-outbox:" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "2pcc-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            // job 1: commit crashes after the checkpoint stored the handles — the epoch's
            // records are durable in the outbox stream but no marker makes them visible
            RedisRuntimeConfig cfg1 = baseConfig("2pcc-job-" + UUID.randomUUID().toString().substring(0, 6), statePrefix).build();
            RedisStreamExecutionEnvironment env1 = RedisStreamExecutionEnvironment.create(redis, cfg1);
            FaultyOutboxSink sink1 = new FaultyOutboxSink(new RedisOutboxSink<>(redis, outboxKey));
            sink1.failCommitOnce.set(true);
            env1.fromMqTopic(topic, "g")
                    .map(m -> new IdempotentRecord<>("id-" + m.getPayload(), (String) m.getPayload()))
                    .addSink(sink1);
            RedisJobClient job1 = env1.executeAsync();
            MessageQueueFactory mq1 = new MessageQueueFactory(redis, cfg1.getMqOptions());
            MessageProducer producer1 = mq1.createProducer();
            for (int i = 0; i < 4; i++) {
                producer1.send(topic, "k" + i, "p" + i).get(5, TimeUnit.SECONDS);
            }
            assertTrue(await(() -> sink1.invoked.get() >= 4, 15_000),
                    "all records should reach the sink, invoked=" + sink1.invoked.get());

            // the checkpoint itself succeeds (store phase); only the sink commit injects a
            // failure, so triggerCheckpointNow still returns the stored checkpoint
            Checkpoint cp = job1.triggerCheckpointNow();
            assertNotNull(cp, "the checkpoint is stored before the failing commit phase");
            String crashedEpoch = sink1.lastEpoch.get();
            assertTrue(await(() -> redis.getStream(outboxKey, StringCodec.INSTANCE).size() >= 4, 5_000),
                    "preCommit must have flushed the records before the injected commit failure");
            assertNull(epochStatus(redis, outboxKey, crashedEpoch),
                    "a crashed commit must leave the epoch without a visibility marker");

            // cancelling does not abort open epochs: the handles stay in the stored
            // checkpoint as the recovery obligation for the next process
            job1.cancel();
            producer1.close();

            // job 2 restores from that checkpoint; the missing sinkCommitted marker makes
            // the recovery path replay recoverAndCommit for every stored handle
            RedisRuntimeConfig cfg2 = RedisRuntimeConfig.builder()
                    .jobName(cfg1.getJobName())
                    .stateKeyPrefix(statePrefix)
                    .restoreFromLatestCheckpoint(true)
                    .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1).consumerPollTimeoutMs(200).build())
                    .build();
            RedisStreamExecutionEnvironment env2 = RedisStreamExecutionEnvironment.create(redis, cfg2);
            env2.fromMqTopic(topic, "g")
                    .map(m -> new IdempotentRecord<>("id-" + m.getPayload(), (String) m.getPayload()))
                    .addSink(new RedisOutboxSink<>(redis, outboxKey));
            try (RedisJobClient job2 = env2.executeAsync()) {
                assertTrue(await(() -> RedisOutboxSink.STATUS_COMMITTED.equals(
                                epochStatus(redis, outboxKey, crashedEpoch)), 15_000),
                        "restore must replay the stored handle of the crashed epoch and mark it committed");
                assertEquals(4, redis.getStream(outboxKey, StringCodec.INSTANCE).size(),
                        "recovery replays only the marker; it must not re-append outbox entries");

                Set<String> ids = new HashSet<>();
                RedisOutboxDispatcher<String> dispatcher = new RedisOutboxDispatcher<>(redis, outboxKey, String.class,
                        d -> ids.add(d.id()),
                        "outbox-e2e", "relay-1", 100, 5, 1_000L, 100L, outboxKey + ":dlq");
                dispatcher.start();
                try {
                    assertTrue(await(() -> ids.size() >= 4, 20_000),
                            "recovered records must become deliverable, got=" + ids);
                    assertEquals(Set.of("id-p0", "id-p1", "id-p2", "id-p3"), ids);
                    assertEquals(0, dlqCount(redis, outboxKey), "recovered records must deliver, not dead-letter");
                } finally {
                    dispatcher.close();
                }
            }
        } finally {
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.getKeys().deleteByPattern(statePrefix + "*");
            redis.getKeys().deleteByPattern(outboxKey + "*");
            redis.shutdown();
        }
    }

    @Test
    void preCommitFailureAbortsEpochAndDeferredAcksRepublishIntoNextEpoch() throws Exception {
        String statePrefix = "streaming:2pca:" + UUID.randomUUID().toString().substring(0, 8);
        String outboxKey = "streaming:2pca-outbox:" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "2pca-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            // deferred acks + a short claim-idle so the records of the aborted epoch come
            // back through pending-entry reclaim and republish into the next epoch
            RedisRuntimeConfig cfg = baseConfig("2pca-job-" + UUID.randomUUID().toString().substring(0, 6), statePrefix)
                    .deferAckUntilCheckpoint(true)
                    .ackDeferredMessagesOnCheckpoint(true)
                    .mqOptions(MqOptions.builder().workerThreads(1).schedulerThreads(1)
                            .consumerPollTimeoutMs(200).claimIdleMs(1_000).build())
                    .build();
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            FaultyOutboxSink sink = new FaultyOutboxSink(new RedisOutboxSink<>(redis, outboxKey));
            env.fromMqTopic(topic, "g")
                    .map(m -> new IdempotentRecord<>("id-" + m.getPayload(), (String) m.getPayload()))
                    .addSink(sink);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                for (int i = 0; i < 4; i++) {
                    producer.send(topic, "k" + i, "p" + i).get(5, TimeUnit.SECONDS);
                }
                assertTrue(await(() -> sink.invoked.get() >= 4, 15_000),
                        "all records should reach the sink, invoked=" + sink.invoked.get());

                // preCommit fails once: the runtime aborts the epoch and the checkpoint is
                // not taken, so triggerCheckpointNow reports null
                sink.failPreCommitOnce.set(true);
                assertNull(job.triggerCheckpointNow(),
                        "a checkpoint whose preCommit fails must not be stored");
                String abortedEpoch = sink.lastEpoch.get();
                assertTrue(await(() -> RedisOutboxSink.STATUS_ABORTED.equals(
                                epochStatus(redis, outboxKey, abortedEpoch)), 5_000),
                        "the runtime must abort the epoch after preCommit failed");
                assertEquals(0, redis.getStream(outboxKey, StringCodec.INSTANCE).size(),
                        "the failed preCommit must not have flushed anything");

                // the deferred acks were skipped, so the records are reclaimed and invoke()
                // runs again into a fresh epoch. Reclaim batches may straddle checkpoints,
                // so keep triggering checkpoints until the dispatcher has seen every id —
                // each successful checkpoint commits whatever epoch is currently buffered.
                assertTrue(await(() -> sink.invoked.get() > 4, 30_000),
                        "records of the aborted epoch must be redelivered, invoked=" + sink.invoked.get());
                Set<String> ids = new HashSet<>();
                RedisOutboxDispatcher<String> dispatcher = new RedisOutboxDispatcher<>(redis, outboxKey, String.class,
                        d -> ids.add(d.id()),
                        "outbox-e2e", "relay-1", 100, 5, 1_000L, 100L, outboxKey + ":dlq");
                dispatcher.start();
                try {
                    long deadline = System.currentTimeMillis() + 45_000;
                    while (ids.size() < 4 && System.currentTimeMillis() < deadline) {
                        assertNotNull(job.triggerCheckpointNow(),
                                "follow-up checkpoints (no injected failure) must store and commit");
                        Thread.sleep(300);
                    }
                    assertTrue(await(() -> ids.size() >= 4, 20_000),
                            "republished records must deliver by id, got=" + ids);
                    assertEquals(Set.of("id-p0", "id-p1", "id-p2", "id-p3"), ids);
                    assertEquals(0, dlqCount(redis, outboxKey), "aborted-epoch recovery must not dead-letter");
                } finally {
                    dispatcher.close();
                }
                producer.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.getKeys().deleteByPattern(statePrefix + "*");
            redis.getKeys().deleteByPattern(outboxKey + "*");
            redis.shutdown();
        }
    }

    /**
     * Deterministic pin for the silent-loss bug the republish scenario above only hits
     * intermittently: after an aborted checkpoint drops the epoch's side effects, the
     * records it consumed are still pending in Redis. If the deferred tracking survived
     * the abort, the NEXT successful checkpoint would claim them via its offsets
     * override and ackAll — advancing the commit frontier and acking ids whose data no
     * committed epoch holds, so reclaim never redelivers them. With the abort-path
     * clear in place the frontier must stay untouched until the records are actually
     * reprocessed and committed. No claim-idle reclaim involved (default 5 min), so
     * this assertion is timing-independent.
     */
    @Test
    void abortedCheckpointMustNotAckDiscardedRecordsOnNextCheckpoint() throws Exception {
        String statePrefix = "streaming:2pcd:" + UUID.randomUUID().toString().substring(0, 8);
        String outboxKey = "streaming:2pcd-outbox:" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "2pcd-" + UUID.randomUUID().toString().substring(0, 8);
        RedissonClient redis = client();
        try {
            RedisRuntimeConfig cfg = baseConfig("2pcd-job-" + UUID.randomUUID().toString().substring(0, 6), statePrefix)
                    .deferAckUntilCheckpoint(true)
                    .ackDeferredMessagesOnCheckpoint(true)
                    .build(); // default claimIdleMs (5 min): no redelivery can race this test
            RedisStreamExecutionEnvironment env = RedisStreamExecutionEnvironment.create(redis, cfg);
            FaultyOutboxSink sink = new FaultyOutboxSink(new RedisOutboxSink<>(redis, outboxKey));
            env.fromMqTopic(topic, "g")
                    .map(m -> new IdempotentRecord<>("id-" + m.getPayload(), (String) m.getPayload()))
                    .addSink(sink);
            RMap<String, String> frontier = redis.getMap(StreamKeys.commitFrontier(topic, 0), StringCodec.INSTANCE);
            try (RedisJobClient job = env.executeAsync()) {
                MessageQueueFactory mq = new MessageQueueFactory(redis, cfg.getMqOptions());
                MessageProducer producer = mq.createProducer();
                for (int i = 0; i < 4; i++) {
                    producer.send(topic, "k" + i, "p" + i).get(5, TimeUnit.SECONDS);
                }
                assertTrue(await(() -> sink.invoked.get() >= 4, 15_000),
                        "all records should reach the sink, invoked=" + sink.invoked.get());

                // checkpoint 1 fails in preCommit: epoch aborted, side effects discarded
                sink.failPreCommitOnce.set(true);
                assertNull(job.triggerCheckpointNow(),
                        "a checkpoint whose preCommit fails must not be stored");
                String abortedEpoch = sink.lastEpoch.get();
                assertTrue(await(() -> RedisOutboxSink.STATUS_ABORTED.equals(
                                epochStatus(redis, outboxKey, abortedEpoch)), 5_000),
                        "the runtime must abort the epoch after preCommit failed");
                assertNull(frontier.get("g"), "no checkpoint has committed yet, frontier must be unset");

                // checkpoint 2 succeeds with an empty (post-abort) view: it must NOT ack
                // the discarded records or move the frontier over them
                assertNotNull(job.triggerCheckpointNow(),
                        "follow-up checkpoint (no injected failure) must store and commit");
                Thread.sleep(1_000);
                assertNull(frontier.get("g"),
                        "an aborted epoch's records must not be acked by the next checkpoint");
                assertEquals(0, redis.getStream(outboxKey, StringCodec.INSTANCE).size(),
                        "the aborted epoch must not have flushed anything");
                producer.close();
            }
        } finally {
            redis.getKeys().deleteByPattern("stream:topic:" + topic + "*");
            redis.getKeys().deleteByPattern(statePrefix + "*");
            redis.getKeys().deleteByPattern(outboxKey + "*");
            redis.shutdown();
        }
    }
}
