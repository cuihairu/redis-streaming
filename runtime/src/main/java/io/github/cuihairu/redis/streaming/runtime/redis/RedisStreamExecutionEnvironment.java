package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.api.stream.KeyedStream;
import io.github.cuihairu.redis.streaming.join.JoinConfig;
import io.github.cuihairu.redis.streaming.join.JoinFunction;
import io.github.cuihairu.redis.streaming.join.operator.Envelope;
import io.github.cuihairu.redis.streaming.join.operator.StreamJoinOperator;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.CheckpointGate;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.JobControlGate;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisJoinEnvelopeCodec;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisLeaderElector;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipeline;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineDefinition;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisStreamBuilder;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisRuntimeCheckpointManager;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Redis-backed execution environment.
 *
 * <p>This environment composes the existing modules (mq/state/checkpoint/etc) into a runnable job.
 * Execution is driven by Redis Streams consumer groups (MQ module).</p>
 *
 * <p>Delivery semantics: at-least-once (ack on successful end-to-end processing).</p>
 */
public final class RedisStreamExecutionEnvironment {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamExecutionEnvironment.class);

    private final RedissonClient redissonClient;
    private final RedisRuntimeConfig config;
    private final MessageQueueFactory mqFactory;
    /** Tenant-aware MQ key view for this environment (derived from the configured MQ options). */
    private StreamKeys keys() {
        return StreamKeys.of(config.getMqOptions());
    }

    private final ObjectMapper objectMapper;

    private final List<RedisPipelineDefinition> pipelineDefinitions = new ArrayList<>();
    private final AtomicBoolean executed = new AtomicBoolean(false);
    private final AtomicInteger streamSeq = new AtomicInteger(0);
    private final java.util.Set<String> usedStreamIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** RT-M7: (topic, group) pairs already registered, so forked pipelines fail fast instead of splitting. */
    private final java.util.Set<TopicGroup> registeredTopicGroups = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Subscription identity of a pipeline; a record type avoids string-delimiter ambiguity. */
    private record TopicGroup(String topic, String consumerGroup) {
    }

    private RedisStreamExecutionEnvironment(RedissonClient redissonClient,
                                            RedisRuntimeConfig config,
                                            MessageQueueFactory mqFactory,
                                            ObjectMapper objectMapper) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.config = config == null ? RedisRuntimeConfig.builder().build() : config;
        this.mqFactory = mqFactory == null
                ? new MessageQueueFactory(this.redissonClient, this.config.getMqOptions())
                : mqFactory;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    /**
     * Build the cross-instance control gate, or null when it is disabled. The lease TTL has to
     * cover a full stop-the-world control operation (drain plus snapshot write) so a slow holder
     * is never mistaken for a crashed one; the renewer runs at a third of the TTL.
     */
    private JobControlGate newJobControlGate() {
        if (!config.isJobControlGateEnabled()) {
            return null;
        }
        long drainMs = Math.max(1L, config.getCheckpointDrainTimeout().toMillis());
        long ttlMs = Math.max(30_000L, drainMs * 2);
        String base = config.getStateKeyPrefix() + ":" + config.getJobName();
        return new JobControlGate(redissonClient, base + ":ctl:excl", base + ":ctl:silence",
                config.getJobInstanceId(), ttlMs, ttlMs / 3);
    }

    private RedisStreamExecutionEnvironment(RedissonClient redissonClient, RedisRuntimeConfig config) {
        this(redissonClient, config, null, null);
    }

    public static RedisStreamExecutionEnvironment create(RedissonClient redissonClient) {
        return new RedisStreamExecutionEnvironment(redissonClient, RedisRuntimeConfig.builder().build());
    }

    public static RedisStreamExecutionEnvironment create(RedissonClient redissonClient, RedisRuntimeConfig config) {
        return new RedisStreamExecutionEnvironment(redissonClient, config);
    }

    static RedisStreamExecutionEnvironment createForTesting(RedissonClient redissonClient,
                                                            RedisRuntimeConfig config,
                                                            MessageQueueFactory mqFactory,
                                                            ObjectMapper objectMapper) {
        return new RedisStreamExecutionEnvironment(redissonClient, config, mqFactory, objectMapper);
    }

    /**
     * Create a streaming source backed by MQ (Redis Streams) returning raw {@link Message}.
     */
    public DataStream<Message> fromMqTopic(String topic, String consumerGroup) {
        return fromMqTopic(topic, consumerGroup, (SubscriptionOptions) null);
    }

    /**
     * Create a streaming source backed by MQ (Redis Streams) returning raw {@link Message}.
     * Allows per-subscription overrides (batch size, poll timeout).
     */
    public DataStream<Message> fromMqTopic(String topic, String consumerGroup, SubscriptionOptions subscriptionOptions) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(consumerGroup, "consumerGroup");
        String streamId = "source-" + streamSeq.incrementAndGet();
        registerStreamId(streamId);
        return RedisStreamBuilder.forMqSource(this, config, redissonClient, objectMapper, streamId, topic, consumerGroup, subscriptionOptions);
    }

    /**
     * Create a streaming source backed by MQ (Redis Streams) with an explicit stable source id.
     *
     * <p>The id is used to derive operator/state identifiers; keep it stable across restarts.</p>
     */
    public DataStream<Message> fromMqTopicWithId(String sourceId, String topic, String consumerGroup) {
        return fromMqTopicWithId(sourceId, topic, consumerGroup, null);
    }

    /**
     * Create a streaming source backed by MQ (Redis Streams) with an explicit stable source id.
     *
     * <p>The id is used to derive operator/state identifiers; keep it stable across restarts.</p>
     */
    public DataStream<Message> fromMqTopicWithId(String sourceId, String topic, String consumerGroup, SubscriptionOptions subscriptionOptions) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(consumerGroup, "consumerGroup");
        String sid = StreamIds.requireValidId(sourceId);
        registerStreamId(sid);
        return RedisStreamBuilder.forMqSource(this, config, redissonClient, objectMapper, sid, topic, consumerGroup, subscriptionOptions);
    }

    /**
     * Create a dual-source streaming pipeline (Phase 3, plan A): one pipeline consuming two MQ
     * topics, each with its own consumer group. Both sources feed one operator chain and one
     * keyed-state space (keyed by source 0's topic/group), so this is the shape a stream-stream
     * join wants without a relay topic: tag both sides into envelopes, key by the join key, and
     * process the join operator on the merged stream.
     *
     * <p><b>Event-time alignment.</b> The pipeline watermark is the minimum over the two
     * sources' candidates, so a window only closes once <em>both</em> sources have advanced past
     * it — one side going quiet holds the join open rather than letting the other side's
     * unmatched rows fire early. A source that goes permanently quiet is unblocked by
     * {@code watermarkIdleTimeout} (RT-M3 idle flush), which fires everything due after the
     * timeout; size that timeout to how late the slower side may run.
     *
     * <p><b>Preconditions.</b> Both topics must be produced with the <em>join key</em> as the MQ
     * key and the <em>same partition count</em>: keyed state is partitioned by the arrival partition,
     * so the same key must land on the same partition in both topics or the two sides never meet
     * in one buffer ({@code executeAsync} fails fast when the partition counts differ). The
     * duplicate (topic, group) guard applies per source.
     *
     * @param topic1          first topic (its group also keys the pipeline's keyed state)
     * @param consumerGroup1  first topic's consumer group
     * @param topic2          second topic
     * @param consumerGroup2  second topic's consumer group
     * @return a stream fed by both sources
     */
    public DataStream<Message> fromMqTopics(String topic1, String consumerGroup1, String topic2, String consumerGroup2) {
        return fromMqTopics(topic1, consumerGroup1, null, topic2, consumerGroup2, null);
    }

    /**
     * Dual-source variant of {@link #fromMqTopics(String, String, String, String)} with
     * per-subscription overrides (batch size, poll timeout).
     */
    public DataStream<Message> fromMqTopics(String topic1, String consumerGroup1, SubscriptionOptions subscriptionOptions1,
                                            String topic2, String consumerGroup2, SubscriptionOptions subscriptionOptions2) {
        Objects.requireNonNull(topic1, "topic1");
        Objects.requireNonNull(consumerGroup1, "consumerGroup1");
        Objects.requireNonNull(topic2, "topic2");
        Objects.requireNonNull(consumerGroup2, "consumerGroup2");
        String streamId = "source-" + streamSeq.incrementAndGet();
        registerStreamId(streamId);
        return RedisStreamBuilder.forMqSources(this, config, redissonClient, objectMapper, streamId,
                List.of(topic1, topic2), List.of(consumerGroup1, consumerGroup2),
                // Arrays.asList, not List.of: a null option means "MQ defaults", which is what the
                // 4-arg convenience overload passes
                Arrays.asList(subscriptionOptions1, subscriptionOptions2));
    }

    /**
     * Dual-source variant with an explicit stable source id (see {@link #fromMqTopicWithId}).
     * The id keeps operator/state identifiers stable across restarts.
     */
    public DataStream<Message> fromMqTopicsWithId(String sourceId,
                                                  String topic1, String consumerGroup1, SubscriptionOptions subscriptionOptions1,
                                                  String topic2, String consumerGroup2, SubscriptionOptions subscriptionOptions2) {
        Objects.requireNonNull(topic1, "topic1");
        Objects.requireNonNull(consumerGroup1, "consumerGroup1");
        Objects.requireNonNull(topic2, "topic2");
        Objects.requireNonNull(consumerGroup2, "consumerGroup2");
        String sid = StreamIds.requireValidId(sourceId);
        registerStreamId(sid);
        return RedisStreamBuilder.forMqSources(this, config, redissonClient, objectMapper, sid,
                List.of(topic1, topic2), List.of(consumerGroup1, consumerGroup2),
                Arrays.asList(subscriptionOptions1, subscriptionOptions2));
    }

    /**
     * Join sugar over two pipelines (union design step 2, Redis half — design option U3, the
     * three-pipeline orchestration): the two input streams are tagged into self-describing
     * {@link Envelope}s using the config's own key selectors and timestamp extractors (current
     * time when an extractor is absent, matching {@code StreamJoiner}) and written to the join
     * topic; a third consumer pipeline reads the join topic, routes by the envelope's join
     * key, and runs the join operator. Returns the consumer's output stream — attach your sink
     * to it before {@link #executeAsync()}.
     *
     * <p>{@code joinTopic} is user-provided and must be <b>stable across restarts</b>: the
     * consumer pipeline's keyed state (the per-key join buffers) is keyed by the
     * (job, topic, group, operator id) tuple, so a renamed topic orphans the previous
     * instance's buffers. It also feeds the consumer's stable source id, so its characters are
     * limited to the source-id charset ({@code [A-Za-z0-9_.-]+}). Its retention follows the MQ stream retention
     * governance like any other topic (the producer path registers it); the sugar never
     * deletes it — deleting would orphan the consumer's state.
     *
     * <p>Consumer-group and source-id hygiene: the group is derived from the topic
     * ({@code topic + "-join"}), the source id is {@code "join-" + topic} — both stable, so
     * operator/state identifiers survive restarts, and the RT-M7 duplicate (topic, group)
     * guard fails fast if anything else tries to share the join topic's group.
     *
     * @param joinTopic    stable topic the two producers write envelopes to (dedicated to this join)
     * @param left         left-hand stream
     * @param right        right-hand stream
     * @param config       join configuration (type, window, retention bounds); validated on entry
     * @param joinFunction the pairing function; invoked with a {@code null} half for unmatched outer-join emissions
     * @param <K>          join key type
     * @param <L>          left element type
     * @param <R>          right element type
     * @param <O>          output element type
     * @return the consumer pipeline's joined-output stream
     */
    public <K, L, R, O> DataStream<O> join(
            String joinTopic,
            DataStream<L> left,
            DataStream<R> right,
            JoinConfig<L, R, K> config,
            JoinFunction<L, R, O> joinFunction) {
        Objects.requireNonNull(joinTopic, "joinTopic");
        if (joinTopic.isBlank()) {
            throw new IllegalArgumentException("joinTopic must not be blank");
        }
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(joinFunction, "joinFunction");
        config.validate();

        DataStream<Envelope<K, L, R>> leftTagged = left.map(l -> Envelope.<K, L, R>forLeft(
                config.getLeftKeySelector().apply(l),
                extractTimestamp(l, config.getLeftTimestampExtractor()), l));
        leftTagged.addSink(new EnvelopeTopicSink<>(joinTopic, mqFactory));

        DataStream<Envelope<K, L, R>> rightTagged = right.map(r -> Envelope.<K, L, R>forRight(
                config.getRightKeySelector().apply(r),
                extractTimestamp(r, config.getRightTimestampExtractor()), r));
        rightTagged.addSink(new EnvelopeTopicSink<>(joinTopic, mqFactory));

        DataStream<Envelope<K, L, R>> envelopes = fromMqTopicWithId(
                        "join-" + joinTopic, joinTopic, joinTopic + "-join")
                .map(m -> RedisJoinEnvelopeCodec.<K, L, R>parse(String.valueOf(m.getPayload())));
        KeyedStream<K, Envelope<K, L, R>> keyed = envelopes.keyBy(Envelope::getJoinKey);
        return keyed.process(StreamJoinOperator.asKeyedProcessFunction(config, joinFunction));
    }

    /** Extract timestamp from element, using current time if no extractor provided
     * (same convention as {@code StreamJoiner} and the join module's sugar). */
    private static <T> long extractTimestamp(T element, Function<T, Long> extractor) {
        return extractor != null ? extractor.apply(element) : System.currentTimeMillis();
    }

    /**
     * Registers a compiled pipeline. Rejects a second pipeline on the same
     * {@code (topic, consumerGroup)} pair (RT-M7): Redis Streams consumer groups
     * distribute each message to a single member, so two pipelines sharing a group
     * <b>split</b> the stream between them instead of each reading every message —
     * and chains forked from one source builder would additionally collide on keyed
     * state names. Independent chains must each get their own consumer group.
     */
    public void registerPipelineDefinition(RedisPipelineDefinition pipelineDefinition) {
        if (executed.get()) {
            throw new IllegalStateException("Cannot register new pipelines after executeAsync() is called");
        }
        Objects.requireNonNull(pipelineDefinition, "pipelineDefinition");
        // the guard is per source: a dual-source pipeline claims every (topic, group) pair it reads
        for (int i = 0; i < pipelineDefinition.sourceCount(); i++) {
            String t = pipelineDefinition.topicAt(i);
            String g = pipelineDefinition.consumerGroupAt(i);
            TopicGroup topicGroup = new TopicGroup(t, g);
            if (!registeredTopicGroups.add(topicGroup)) {
                throw new IllegalStateException(
                        "Duplicate pipeline for topic '" + t + "' and consumer group '" + g + "': two pipelines "
                                + "sharing one consumer group split the stream between them instead of each reading "
                                + "every message (Redis Streams consumer-group semantics), and chains forked from the "
                                + "same source also collide on keyed-state names. Give each independent chain its own "
                                + "consumer group, e.g. env.fromMqTopic(\"" + t + "\", \"" + g + "-fork2\").");
            }
        }
        pipelineDefinitions.add(pipelineDefinition);
    }

    private static final String ENSURE_GROUP_LUA =
        "local exists=false \n" +
        "local info = redis.pcall('XINFO','GROUPS', KEYS[1]) \n" +
        "if type(info) == 'table' and info.err == nil then \n" +
        "  for i=1,#info do local g = info[i]; for j=1,#g,2 do if g[j]=='name' and g[j+1]==ARGV[1] then exists=true; break end end if exists then break end end \n" +
        "end \n" +
        "if exists then return 'EXISTS' end \n" +
        "local r = redis.pcall('XGROUP','CREATE', KEYS[1], ARGV[1], ARGV[2], 'MKSTREAM') \n" +
        "if type(r)=='table' and r.err then if string.find(r.err,'BUSYGROUP') then return 'EXISTS' else return r.err end end \n" +
        "return r";

    /**
     * Start all registered pipelines asynchronously.
     */
    public RedisJobClient executeAsync() {
        if (pipelineDefinitions.isEmpty()) {
            throw new IllegalStateException("No pipelines registered. Add at least one sink before executeAsync().");
        }
        if (!executed.compareAndSet(false, true)) {
            throw new IllegalStateException("executeAsync() can only be called once per environment");
        }
        try {
            RedisRuntimeMetrics.get().incJobStarted(config.getJobName());
        } catch (Exception ignore) {
        }
        // copy-on-write: scaleParallelism mutates these under the control gate while
        // cancel() iterates them without any lock
        List<MessageConsumer> consumers = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<RedisPipelineRunner<?>> runners = new java.util.concurrent.CopyOnWriteArrayList<>();
        // per-subtask bookkeeping for scaleParallelism (mutated only under the same control gate)
        List<Subtask> subtasks = new ArrayList<>();
        // monotonically increasing consumer-name sequence across resizes (never reused)
        AtomicLong consumerSeq = new AtomicLong(0);
        ScheduledExecutorService sharedTimerExecutor = null;
        TopicPartitionRegistry partitionRegistry = new TopicPartitionRegistry(redissonClient);
        RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
        // Leader election (optional): the leader is the only instance running coordination
        // duties (periodic checkpoint scheduling), preventing split-brain double-writes.
        RedisLeaderElector leaderElector = null;
        AtomicBoolean isLeader = new AtomicBoolean(false);
        if (config.isLeaderElectionEnabled()) {
            leaderElector = new RedisLeaderElector(redissonClient, config);
            try {
                if (leaderElector.tryAcquireLeadership()) {
                    isLeader.set(true);
                    long token = leaderElector.nextFencingToken();
                    log.info("Acquired leadership for job {} (instance {}, fencingToken={})",
                            config.getJobName(), config.getJobInstanceId(), token);
                } else {
                    log.info("Leadership held by instance {} (jobName={}, instance={}); running as follower",
                            leaderElector.currentLeader(), config.getJobName(), config.getJobInstanceId());
                }
            } catch (Exception e) {
                log.warn("Leader election probe failed (jobName={})", config.getJobName(), e);
            }
        }
        RedisRuntimeCheckpointManager checkpointManager =
                new RedisRuntimeCheckpointManager(redissonClient, config, leaderElector);
        List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys = new ArrayList<>();
        AtomicReference<ScheduledExecutorService> checkpointExecutorRef = new AtomicReference<>();
        ScheduledExecutorService leaderRenewExecutor = null;
        // control gate: serializes checkpoint flows and scaleParallelism; the full-silence
        // level is what the aligned checkpoint and scale both need (see CheckpointGate)
        CheckpointGate gate = new CheckpointGate();
        // cross-instance counterpart of the gate above: clients of the same job serialize their
        // control operations through Redis leases, and null leaves single-instance behavior intact
        JobControlGate jobControlGate = newJobControlGate();
        DeferredAcks deferredAcks = new DeferredAcks();
        TwoPhaseEpochTracker twoPhaseEpochs = new TwoPhaseEpochTracker();
        // split collaborators: checkpoint flow/scheduling and subtask wiring live outside
        // the environment facade so the job lifecycle is not one monolith
        RedisCheckpointFlow checkpointFlow = new RedisCheckpointFlow(config, redissonClient, jobControlGate);
        RedisCheckpointScheduler checkpointScheduler = new RedisCheckpointScheduler(config, checkpointFlow);
        RedisSubtaskLauncher subtaskLauncher = new RedisSubtaskLauncher(config);
        // set when at least one runner replayed a stored transaction during restore
        final java.util.concurrent.atomic.AtomicBoolean twoPhaseRecoveryReplayed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<Long> restoredCheckpointIdRef = new java.util.concurrent.atomic.AtomicReference<>(null);
        // full checkpoint object kept for two-phase-commit recovery (runner build happens
        // after the restore, and the compensation needs the stored txn handles)
        java.util.concurrent.atomic.AtomicReference<Checkpoint> restoredCheckpointRef =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        subtaskLauncher.warnDeferAckConfiguration();

        try {
            for (RedisPipelineDefinition def : pipelineDefinitions) {
                // one checkpoint key per source: offsets are tracked per (topic, group), so a
                // dual-source pipeline restores each side independently
                for (int i = 0; i < def.sourceCount(); i++) {
                    pipelineKeys.add(new RedisRuntimeCheckpointManager.PipelineKey(def.topicAt(i), def.consumerGroupAt(i)));
                }
            }
            validateMultiSourcePartitionCounts(pipelineDefinitions, partitionRegistry);
            if (config.isRestoreFromLatestCheckpoint()) {
                // RT-M4: a requested restore that fails must abort the start instead of
                // silently consuming from scratch without the restored state — the outer
                // catch cleans everything up and rethrows. No checkpoint yet (null) still
                // starts fresh.
                Checkpoint restored = checkpointManager.restoreFromLatestCheckpointOrNull(pipelineKeys);
                if (restored != null) {
                    restoredCheckpointIdRef.set(restored.getCheckpointId());
                    restoredCheckpointRef.set(restored);
                    log.info("Restored Redis runtime job from checkpoint {} (jobName={})",
                            restoredCheckpointIdRef.get(), config.getJobName());
                }
            }

            int pipelineIndex = 0;
            for (RedisPipelineDefinition def : pipelineDefinitions) {
                RedisPipeline<?> p = def.freeze();
                int parallelism = Math.max(1, config.getPipelineParallelism());
                for (int subtask = 0; subtask < parallelism; subtask++) {
                    if (sharedTimerExecutor == null) {
                        int threads = Math.max(1, config.getTimerThreads());
                        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(threads);
                        ex.setRemoveOnCancelPolicy(true);
                        sharedTimerExecutor = ex;
                    }
                    RedisPipelineRunner<?> runner = p.buildRunner(sharedTimerExecutor);
                    runners.add(runner);
                    Long restoredCheckpointId = restoredCheckpointIdRef.get();
                    if (restoredCheckpointId != null) {
                        runner.onCheckpointRestore(restoredCheckpointId);
                        Checkpoint restoredCp = restoredCheckpointRef.get();
                        if (restoredCp != null
                                && checkpointFlow.recoverTwoPhaseTransactions(runner, runners.size() - 1,
                                        checkpointManager, restoredCp)) {
                            twoPhaseRecoveryReplayed.set(true);
                        }
                    }

                    // register before subscribe so a concurrent cancel() cannot miss it
                    String consumerName = config.getJobName() + "-" + config.getJobInstanceId() + "-"
                            + consumerSeq.incrementAndGet();
                    MessageConsumer consumer = mqFactory.createConsumer(consumerName);
                    consumers.add(consumer);

                    if (config.isRestoreConsumerGroupFromCommitFrontier()) {
                        for (int s = 0; s < p.sourceCount(); s++) {
                            ensureConsumerGroupStartsFromCommitFrontierIfMissing(
                                    partitionRegistry, script, ENSURE_GROUP_LUA, p.topicAt(s), p.consumerGroupAt(s));
                        }
                    }

                    subtaskLauncher.startSubtaskConsumer(consumer, consumerName, p, subtask, parallelism, runner, deferredAcks);
                    subtasks.add(new Subtask(pipelineIndex, runner, consumer));
                }
                pipelineIndex++;
            }

            // every runner replayed its stored transactions: the restored checkpoint is now
            // genuinely sink-committed, so mark it once (not per runner) to keep a later
            // restore from replaying the same handles again
            if (twoPhaseRecoveryReplayed.get()) {
                checkpointFlow.markRecoveredCheckpointCommitted(checkpointManager, restoredCheckpointRef.get());
            }

            if (config.isLeaderElectionEnabled()) {
                leaderRenewExecutor = checkpointScheduler.startLeaderCoordination(checkpointExecutorRef, consumers, runners,
                        checkpointManager, pipelineKeys, deferredAcks, twoPhaseEpochs, gate,
                        isLeader, leaderElector);
            } else {
                checkpointScheduler.startCheckpointExecutor(checkpointExecutorRef, consumers, runners, checkpointManager,
                        pipelineKeys, deferredAcks, twoPhaseEpochs, gate);
            }
        } catch (Exception e) {
            QuietClose.stopConsumersQuietly(consumers);
            QuietClose.closeRunnersQuietly(runners);
            QuietClose.shutdownExecutorQuietly(sharedTimerExecutor);
            QuietClose.shutdownExecutorQuietly(checkpointExecutorRef.get());
            QuietClose.shutdownExecutorQuietly(leaderRenewExecutor);
            if (jobControlGate != null) {
                // release-then-close: a held lease must not block the restarted job's first
                // control operation for a full TTL; the CAS release cannot free another
                // instance's lease
                jobControlGate.endControlOp();
                jobControlGate.close();
            }
            if (leaderElector != null) {
                try {
                    leaderElector.releaseLeadership();
                } catch (Exception ignore) {
                }
            }
            executed.set(false);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException("Failed to start Redis runtime job", e);
        }

        return RedisLaunchedJobClient.create(checkpointExecutorRef, leaderRenewExecutor, leaderElector, isLeader,
                sharedTimerExecutor, consumers, runners, subtasks, consumerSeq,
                checkpointManager, pipelineKeys, deferredAcks, twoPhaseEpochs, gate, jobControlGate,
                restoredCheckpointIdRef, config, pipelineDefinitions, mqFactory, executed,
                checkpointFlow, subtaskLauncher);
    }

    /**
     * Start the periodic checkpoint scheduler. With leader election disabled this runs on
     * every instance (previous behavior); with election enabled it runs only on the leader.
     */

    /**
     * Handle for a job started by {@link #executeAsync()}: cancellation, manual checkpoint
     * triggering, pause/resume and diagnostics.
     */

    /** One running subtask: a runner executing the pipeline plus its partition-pinned consumer. */

    /**
     * Phase 3 dual-source precondition: every source of a multi-source pipeline must carry the
     * same partition count. Keyed state (the join buffers) is partitioned by the <em>arrival</em>
     * partition and one runner consumes all sources, so a key that hashes to partition 2 of the
     * left topic but partition 0 of the right topic never meets in one buffer — the join would
     * silently emit nothing but the unmatched rows. Fail fast at start rather than at runtime.
     *
     * <p>Single-source pipelines are untouched: the check is about co-locating two sources in one
     * keyed-state space, which only the dual-source form does.
     */
    private void validateMultiSourcePartitionCounts(List<RedisPipelineDefinition> definitions,
                                                    TopicPartitionRegistry partitionRegistry) {
        for (RedisPipelineDefinition def : definitions) {
            if (def.sourceCount() < 2) {
                continue;
            }
            int expected = Math.max(1, partitionRegistry.getPartitionCount(def.topicAt(0)));
            for (int i = 1; i < def.sourceCount(); i++) {
                int actual = Math.max(1, partitionRegistry.getPartitionCount(def.topicAt(i)));
                if (actual != expected) {
                    throw new IllegalStateException(
                            "Dual-source pipeline sources must have the same partition count, but topic '"
                                    + def.topicAt(0) + "' has " + expected + " and topic '" + def.topicAt(i)
                                    + "' has " + actual + ": keyed state is partitioned by the arrival partition, "
                                    + "so a join key landing on different partitions in the two topics never "
                                    + "meets in one buffer. Produce both topics with the same partition count (and "
                                    + "the same key so the partition hash agrees), or route both sides through one "
                                    + "relay topic with env.join(...), which tags them into a single source.");
                }
            }
        }
    }

    private void ensureConsumerGroupStartsFromCommitFrontierIfMissing(TopicPartitionRegistry partitionRegistry,
                                                                      RScript script,
                                                                      String ensureGroupLua,
                                                                      String topic,
                                                                      String consumerGroup) {
        try {
            int pc = Math.max(1, partitionRegistry.getPartitionCount(topic));
            for (int pid = 0; pid < pc; pid++) {
                String frontierKey = keys().commitFrontier(topic, pid);
                String committedId = null;
                try {
                    // MQ-11: the frontier hash is plain text "ms-seq" written by the ack
                    // path's Lua CAS (StringCodec), regardless of the client codec
                    RMap<String, String> frontier = redissonClient.getMap(frontierKey,
                            org.redisson.client.codec.StringCodec.INSTANCE);
                    committedId = frontier.get(consumerGroup);
                } catch (Exception ignore) {
                }
                String startId = (committedId == null || committedId.isBlank()) ? "0-0" : committedId;
                String streamKey = keys().partitionStream(topic, pid);
                try {
                    Object r = script.eval(RScript.Mode.READ_WRITE, ensureGroupLua, RScript.ReturnType.STRING,
                            java.util.Collections.singletonList(streamKey), consumerGroup, startId);
                    String s = String.valueOf(r);
                    if (!"OK".equals(s) && !"EXISTS".equals(s)) {
                        log.debug("Ensure group from commit frontier returned {} for {} @ {} (startId={})",
                                s, consumerGroup, streamKey, startId);
                    } else if ("OK".equals(s) && !"0-0".equals(startId)) {
                        log.info("Restored missing consumer group from commit frontier: topic={}, group={}, partition={}, startId={}",
                                topic, consumerGroup, pid, startId);
                    }
                } catch (Exception e) {
                    log.debug("Ensure group from commit frontier ignored for {} @ {}: {}", consumerGroup, streamKey, e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to ensure consumer group from commit frontier: topic={}, group={}", topic, consumerGroup, e);
        }
    }

    private void registerStreamId(String streamId) {
        if (!usedStreamIds.add(streamId)) {
            throw new IllegalStateException("Duplicate sourceId/streamId: " + streamId);
        }
    }

}
