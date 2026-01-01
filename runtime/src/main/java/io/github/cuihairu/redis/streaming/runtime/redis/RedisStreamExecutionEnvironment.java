package io.github.cuihairu.redis.streaming.runtime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.api.stream.DataStream;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.MqHeaders;
import io.github.cuihairu.redis.streaming.mq.SubscriptionOptions;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
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
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

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
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+");

    private final RedissonClient redissonClient;
    private final RedisRuntimeConfig config;
    private final MessageQueueFactory mqFactory;
    private final ObjectMapper objectMapper;

    private final List<RedisPipelineDefinition> pipelineDefinitions = new ArrayList<>();
    private final AtomicBoolean executed = new AtomicBoolean(false);
    private final AtomicInteger streamSeq = new AtomicInteger(0);
    private final java.util.Set<String> usedStreamIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        String sid = requireValidId(sourceId);
        registerStreamId(sid);
        return RedisStreamBuilder.forMqSource(this, config, redissonClient, objectMapper, sid, topic, consumerGroup, subscriptionOptions);
    }

    public void registerPipelineDefinition(RedisPipelineDefinition pipelineDefinition) {
        if (executed.get()) {
            throw new IllegalStateException("Cannot register new pipelines after executeAsync() is called");
        }
        pipelineDefinitions.add(Objects.requireNonNull(pipelineDefinition, "pipelineDefinition"));
    }

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
        List<MessageConsumer> consumers = new ArrayList<>();
        List<RedisPipelineRunner<?>> runners = new ArrayList<>();
        TopicPartitionRegistry partitionRegistry = new TopicPartitionRegistry(redissonClient);
        RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
        RedisRuntimeCheckpointManager checkpointManager = new RedisRuntimeCheckpointManager(redissonClient, config);
        List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys = new ArrayList<>();
        ScheduledExecutorService checkpointExecutor = null;
        AtomicBoolean checkpointing = new AtomicBoolean(false);
        DeferredAcks deferredAcks = new DeferredAcks();
        Long restoredCheckpointId = null;
        warnDeferAckConfiguration();
        final String ensureGroupLua =
                "local exists=false \n" +
                "local info = redis.pcall('XINFO','GROUPS', KEYS[1]) \n" +
                "if type(info) == 'table' and info.err == nil then \n" +
                "  for i=1,#info do local g = info[i]; for j=1,#g,2 do if g[j]=='name' and g[j+1]==ARGV[1] then exists=true; break end end if exists then break end end \n" +
                "end \n" +
                "if exists then return 'EXISTS' end \n" +
                "local r = redis.pcall('XGROUP','CREATE', KEYS[1], ARGV[1], ARGV[2], 'MKSTREAM') \n" +
                "if type(r)=='table' and r.err then if string.find(r.err,'BUSYGROUP') then return 'EXISTS' else return r.err end end \n" +
                "return r";

        try {
            for (RedisPipelineDefinition def : pipelineDefinitions) {
                pipelineKeys.add(new RedisRuntimeCheckpointManager.PipelineKey(def.topic(), def.consumerGroup()));
            }
            if (config.isRestoreFromLatestCheckpoint()) {
                try {
                    Checkpoint restored = checkpointManager.restoreFromLatestCheckpointOrNull(pipelineKeys);
                    if (restored != null) {
                        restoredCheckpointId = restored.getCheckpointId();
                        log.info("Restored Redis runtime job from checkpoint {} (jobName={})", restoredCheckpointId, config.getJobName());
                    }
                } catch (Exception e) {
                    log.warn("Failed to restore Redis runtime job from latest checkpoint (jobName={})", config.getJobName(), e);
                }
            }

            for (RedisPipelineDefinition def : pipelineDefinitions) {
                RedisPipeline<?> p = def.freeze();
                int parallelism = Math.max(1, config.getPipelineParallelism());
                for (int subtask = 0; subtask < parallelism; subtask++) {
                    RedisPipelineRunner<?> runner = p.buildRunner();
                    runners.add(runner);
                    if (restoredCheckpointId != null) {
                        runner.onCheckpointRestore(restoredCheckpointId);
                    }

                    String consumerName = config.getJobName() + "-" + config.getJobInstanceId() + "-" + (consumers.size() + 1);
                    MessageConsumer consumer = mqFactory.createConsumer(consumerName);
                    consumers.add(consumer);

                    if (config.isRestoreConsumerGroupFromCommitFrontier()) {
                        ensureConsumerGroupStartsFromCommitFrontierIfMissing(
                                partitionRegistry, script, ensureGroupLua, p.topic(), p.consumerGroup());
                    }

                    MessageHandler handler = (Message m) -> {
                        long startNs = System.nanoTime();
                        try {
                            boolean ok = runner.handle(m);
                            try {
                                RedisRuntimeMetrics.get().recordHandleLatency(config.getJobName(), p.topic(), p.consumerGroup(),
                                        (System.nanoTime() - startNs) / 1_000_000);
                                if (ok) {
                                    RedisRuntimeMetrics.get().incHandleSuccess(config.getJobName(), p.topic(), p.consumerGroup());
                                } else {
                                    RedisRuntimeMetrics.get().incHandleError(config.getJobName(), p.topic(), p.consumerGroup());
                                }
                            } catch (Exception ignore) {
                            }
                            if (!ok) {
                                return MessageHandleResult.RETRY;
                            }
                            if (config.isDeferAckUntilCheckpoint()) {
                                markDeferAck(m, deferredAcks, p.topic(), p.consumerGroup());
                            }
                            return MessageHandleResult.SUCCESS;
                        } catch (Exception e) {
                            annotateRuntimeError(m, p.consumerGroup(), e);
                            try {
                                RedisRuntimeMetrics.get().recordHandleLatency(config.getJobName(), p.topic(), p.consumerGroup(),
                                        (System.nanoTime() - startNs) / 1_000_000);
                                RedisRuntimeMetrics.get().incHandleError(config.getJobName(), p.topic(), p.consumerGroup());
                            } catch (Exception ignore) {
                            }
                            try {
                                String id = m == null ? null : m.getId();
                                String key = m == null ? null : m.getKey();
                                log.error("Redis runtime pipeline failed (jobName={}, topic={}, group={}, consumer={}, id={}, key={})",
                                        config.getJobName(), p.topic(), p.consumerGroup(), consumerName, id, key, e);
                            } catch (Exception ignore) {
                                // best-effort logging only
                            }
                            return config.getProcessingErrorResult();
                        }
                    };

                    io.github.cuihairu.redis.streaming.mq.SubscriptionOptions opts = p.subscriptionOptions();
                    if (parallelism > 1) {
                        io.github.cuihairu.redis.streaming.mq.SubscriptionOptions.Builder b = io.github.cuihairu.redis.streaming.mq.SubscriptionOptions.builder();
                        if (opts != null) {
                            try {
                                Integer bc = opts.getBatchCount();
                                if (bc != null) b.batchCount(bc);
                            } catch (Exception ignore) {
                            }
                            try {
                                Long to = opts.getPollTimeoutMs();
                                if (to != null) b.pollTimeoutMs(to);
                            } catch (Exception ignore) {
                            }
                        }
                        b.partitionModulo(parallelism).partitionRemainder(subtask);
                        opts = b.build();
                    }

                    try {
                        consumer.subscribe(p.topic(), p.consumerGroup(), handler, opts);
                        consumer.start();
                        try {
                            RedisRuntimeMetrics.get().incPipelineStarted(config.getJobName(), p.topic(), p.consumerGroup());
                        } catch (Exception ignore) {
                        }
                    } catch (Exception e) {
                        try {
                            RedisRuntimeMetrics.get().incPipelineStartFailed(config.getJobName(), p.topic(), p.consumerGroup());
                        } catch (Exception ignore) {
                        }
                        throw e;
                    }
                }
            }

            Duration interval = config.getCheckpointInterval();
            if (interval != null && !interval.isZero() && !interval.isNegative()) {
                ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);
                ex.setRemoveOnCancelPolicy(true);
                checkpointExecutor = ex;
                long ms = Math.max(50L, interval.toMillis());
                checkpointExecutor.scheduleWithFixedDelay(() -> {
                    if (!checkpointing.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        triggerCheckpointInternal(consumers, runners, checkpointManager, pipelineKeys, deferredAcks);
                    } catch (Exception e) {
                        log.debug("Periodic checkpoint failed (jobName={})", config.getJobName(), e);
                    } finally {
                        checkpointing.set(false);
                    }
                }, ms, ms, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            for (MessageConsumer c : consumers) {
                try {
                    c.stop();
                } catch (Exception ignore) {
                }
                try {
                    c.close();
                } catch (Exception ignore) {
                }
            }
            for (RedisPipelineRunner<?> r : runners) {
                try {
                    r.close();
                } catch (Exception ignore) {
                }
            }
            if (checkpointExecutor != null) {
                try {
                    checkpointExecutor.shutdownNow();
                } catch (Exception ignore) {
                }
            }
            executed.set(false);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException("Failed to start Redis runtime job", e);
        }

        ScheduledExecutorService finalCheckpointExecutor = checkpointExecutor;
        return new RedisJobClient() {
            private final CountDownLatch stopped = new CountDownLatch(1);
            private final AtomicBoolean canceled = new AtomicBoolean(false);

            @Override
	            public void cancel() {
	                if (!canceled.compareAndSet(false, true)) {
	                    return;
	                }
                try {
                    try {
                        RedisRuntimeMetrics.get().incJobCanceled(config.getJobName());
                    } catch (Exception ignore) {
                    }
                    if (finalCheckpointExecutor != null) {
                        try {
                            finalCheckpointExecutor.shutdownNow();
                        } catch (Exception ignore) {
                        }
                    }
                    for (MessageConsumer c : consumers) {
                        try {
                            c.stop();
                        } catch (Exception ignore) {
                        }
                        try {
                            c.close();
                        } catch (Exception ignore) {
                        }
                    }
                    for (RedisPipelineRunner<?> r : runners) {
                        try {
                            r.close();
                        } catch (Exception ignore) {
                        }
                    }
                } finally {
                    executed.set(false);
                    stopped.countDown();
	                }
	            }

	            @Override
            public Checkpoint triggerCheckpointNow() {
                if (canceled.get()) {
                    return null;
                }
                if (!checkpointing.compareAndSet(false, true)) {
                    return null;
                }
                try {
                    return triggerCheckpointInternal(consumers, runners, checkpointManager, pipelineKeys, deferredAcks);
                } finally {
                    checkpointing.set(false);
                }
            }

	            @Override
	            public Checkpoint getLatestCheckpoint() {
	                return checkpointManager.getLatestCheckpoint();
	            }

	            @Override
	            public void pause() {
	                for (MessageConsumer c : consumers) {
	                    if (c instanceof PausableMessageConsumer pc) {
	                        try {
	                            pc.pause();
	                        } catch (Exception ignore) {
	                        }
	                    }
	                }
	            }

	            @Override
	            public void resume() {
	                for (MessageConsumer c : consumers) {
	                    if (c instanceof PausableMessageConsumer pc) {
	                        try {
	                            pc.resume();
	                        } catch (Exception ignore) {
	                        }
	                    }
	                }
	            }

	            @Override
	            public long inFlight() {
	                long total = 0;
	                boolean any = false;
	                for (MessageConsumer c : consumers) {
	                    if (c instanceof PausableMessageConsumer pc) {
	                        any = true;
	                        try {
	                            total += Math.max(0, pc.inFlight());
	                        } catch (Exception ignore) {
	                        }
	                    }
	                }
	                return any ? total : -1L;
	            }

	            @Override
	            public boolean awaitTermination(Duration timeout) throws InterruptedException {
	                Duration t = timeout == null ? Duration.ZERO : timeout;
	                return stopped.await(Math.max(0, t.toMillis()), TimeUnit.MILLISECONDS);
	            }
        };
    }

    private void warnDeferAckConfiguration() {
        if (!config.isDeferAckUntilCheckpoint()) {
            return;
        }
        Duration interval = config.getCheckpointInterval();
        boolean periodicCheckpoint = interval != null && !interval.isZero() && !interval.isNegative();
        if (!periodicCheckpoint) {
            log.warn("deferAckUntilCheckpoint is enabled but periodic checkpointing is disabled; messages will remain pending until triggerCheckpointNow() is called (jobName={})",
                    config.getJobName());
        }

        long claimIdleMs = 0L;
        try {
            if (config.getMqOptions() != null) {
                claimIdleMs = Math.max(0L, config.getMqOptions().getClaimIdleMs());
            }
        } catch (Exception ignore) {
        }
        if (claimIdleMs <= 0) {
            return;
        }

        Duration drainTimeout = config.getCheckpointDrainTimeout();
        long drainMs = drainTimeout == null ? 0L : Math.max(0L, drainTimeout.toMillis());
        long intervalMs = periodicCheckpoint ? Math.max(0L, interval.toMillis()) : 0L;

        if (drainMs > 0 && drainMs >= claimIdleMs) {
            log.warn("deferAckUntilCheckpoint may be unsafe: checkpointDrainTimeout >= mq.claimIdleMs (jobName={}, checkpointDrainTimeoutMs={}, claimIdleMs={})",
                    config.getJobName(), drainMs, claimIdleMs);
        } else if (intervalMs > 0 && intervalMs + drainMs >= claimIdleMs) {
            log.warn("deferAckUntilCheckpoint may be unsafe: mq.claimIdleMs is smaller than checkpoint interval + drain timeout (jobName={}, checkpointIntervalMs={}, checkpointDrainTimeoutMs={}, claimIdleMs={})",
                    config.getJobName(), intervalMs, drainMs, claimIdleMs);
        }
    }

    private Checkpoint triggerCheckpointInternal(List<MessageConsumer> consumers,
                                                 List<RedisPipelineRunner<?>> runners,
                                                 RedisRuntimeCheckpointManager checkpointManager,
                                                 List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys,
                                                 DeferredAcks deferredAcks) {
        if (consumers == null || consumers.isEmpty()) {
            return null;
        }
        boolean pausable = true;
        for (MessageConsumer c : consumers) {
	            if (!(c instanceof PausableMessageConsumer)) {
	                pausable = false;
	                break;
	            }
	        }
	        if (!pausable) {
	            log.warn("Skip checkpoint: consumer is not pausable (jobName={})", config.getJobName());
	            return null;
	        }

        for (MessageConsumer c : consumers) {
            ((PausableMessageConsumer) c).pause();
        }

        long checkpointId = checkpointManager.allocateCheckpointId();
        try {
            Duration drainTimeout = config.getCheckpointDrainTimeout();
            long deadline = System.currentTimeMillis() + Math.max(0, drainTimeout == null ? 0 : drainTimeout.toMillis());
            while (true) {
                long total = 0;
	                for (MessageConsumer c : consumers) {
	                    total += ((PausableMessageConsumer) c).inFlight();
	                }
	                if (total <= 0) {
	                    break;
	                }
	                if (drainTimeout != null && drainTimeout.toMillis() > 0 && System.currentTimeMillis() > deadline) {
	                    log.warn("Checkpoint drain timeout (jobName={}, inFlight={})", config.getJobName(), total);
	                    break;
	                }
	                try {
	                    Thread.sleep(20);
	                } catch (InterruptedException ie) {
	                    Thread.currentThread().interrupt();
                    break;
                }
            }

            for (RedisPipelineRunner<?> r : runners) {
                try {
                    r.onCheckpointStart(checkpointId);
                } catch (Exception e) {
                    for (RedisPipelineRunner<?> rr : runners) {
                        rr.onCheckpointAbort(checkpointId, e);
                    }
                    return null;
                }
            }

            Map<String, Map<Integer, String>> offsetsOverride = null;
            if (config.isDeferAckUntilCheckpoint()) {
                offsetsOverride = deferredAcks.snapshotOffsets();
            }

            Checkpoint cp = checkpointManager.triggerCheckpoint(checkpointId, pipelineKeys, offsetsOverride);
            if (cp == null) {
                for (RedisPipelineRunner<?> r : runners) {
                    r.onCheckpointAbort(checkpointId, null);
                }
                return null;
            }

            try {
                for (RedisPipelineRunner<?> r : runners) {
                    r.onCheckpointComplete(checkpointId);
                }
                checkpointManager.markSinkCommitted(cp);
            } catch (Exception e) {
                log.error("Sink commit on checkpoint failed (jobName={}, checkpointId={})", config.getJobName(), checkpointId, e);
                for (RedisPipelineRunner<?> r : runners) {
                    r.onCheckpointAbort(checkpointId, e);
                }
                return cp;
            }

            if (config.isDeferAckUntilCheckpoint()) {
                if (config.isAckDeferredMessagesOnCheckpoint()) {
                    deferredAcks.ackAll(redissonClient);
                } else {
                    deferredAcks.clear();
                }
            }
            return cp;
        } finally {
            try {
                for (MessageConsumer c : consumers) {
                    if (c instanceof PausableMessageConsumer pc) {
	                        pc.resume();
	                    }
	                }
	            } catch (Exception ignore) {
            }
        }
    }

    private void markDeferAck(Message message, DeferredAcks deferredAcks, String topic, String consumerGroup) {
        if (message == null) {
            return;
        }
        try {
            String id = message.getId();
            if (id == null || id.isBlank()) {
                return;
            }
            int pid = -1;
            try {
                if (message.getHeaders() != null) {
                    String s = message.getHeaders().get(MqHeaders.PARTITION_ID);
                    if (s != null && !s.isBlank()) {
                        pid = Integer.parseInt(s);
                    }
                }
            } catch (Exception ignore) {
            }
            if (pid < 0) {
                return;
            }
            java.util.Map<String, String> headers = message.getHeaders();
            if (headers == null) {
                headers = new java.util.HashMap<>();
                message.setHeaders(headers);
            } else if (!(headers instanceof java.util.HashMap)) {
                headers = new java.util.HashMap<>(headers);
                message.setHeaders(headers);
            }
            headers.put(MqHeaders.DEFER_ACK, "true");
            deferredAcks.record(topic, consumerGroup, pid, id);
        } catch (Exception ignore) {
        }
    }

    private static final class DeferredAcks {
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>>> byPipeline =
                new java.util.concurrent.ConcurrentHashMap<>();

        void record(String topic, String consumerGroup, int partitionId, String messageId) {
            String key = topic + "|" + consumerGroup;
            byPipeline.computeIfAbsent(key, k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .computeIfAbsent(partitionId, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                    .add(messageId);
        }

        Map<String, Map<Integer, String>> snapshotOffsets() {
            Map<String, Map<Integer, String>> out = new HashMap<>();
            for (Map.Entry<String, java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>>> e : byPipeline.entrySet()) {
                Map<Integer, String> per = new HashMap<>();
                for (Map.Entry<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>> pe : e.getValue().entrySet()) {
                    String last = null;
                    for (String id : pe.getValue()) {
                        last = id;
                    }
                    if (last != null && !last.isBlank()) {
                        per.put(pe.getKey(), last);
                    }
                }
                if (!per.isEmpty()) {
                    out.put(e.getKey(), per);
                }
            }
            return out;
        }

        void clear() {
            for (java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>> per : byPipeline.values()) {
                for (java.util.concurrent.ConcurrentLinkedQueue<String> q : per.values()) {
                    try {
                        q.clear();
                    } catch (Exception ignore) {
                    }
                }
            }
            byPipeline.clear();
        }

        void ackAll(RedissonClient redissonClient) {
            if (redissonClient == null) {
                return;
            }
            for (Map.Entry<String, java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>>> e : byPipeline.entrySet()) {
                String[] parts = e.getKey().split("\\|", 2);
                String topic = parts.length > 0 ? parts[0] : "";
                String group = parts.length > 1 ? parts[1] : "";
                for (Map.Entry<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>> pe : e.getValue().entrySet()) {
                    int pid = pe.getKey();
                    java.util.List<StreamMessageId> ids = new java.util.ArrayList<>();
                    String maxId = null;
                    while (true) {
                        String id = pe.getValue().poll();
                        if (id == null) break;
                        ids.add(parseStreamId(id));
                        maxId = id;
                    }
                    if (ids.isEmpty()) {
                        continue;
                    }
                    try {
                        RStream<String, Object> stream = redissonClient.getStream(StreamKeys.partitionStream(topic, pid), StringCodec.INSTANCE);
                        stream.ack(group, ids.toArray(new StreamMessageId[0]));
                    } catch (Exception ex) {
                        log.warn("Failed to ack deferred messages (topic={}, group={}, partition={}, count={})", topic, group, pid, ids.size(), ex);
                    }
                    if (maxId != null) {
                        try {
                            @SuppressWarnings("unchecked")
                            RMap<String, String> frontier = redissonClient.getMap(StreamKeys.commitFrontier(topic, pid));
                            String prev = frontier.get(group);
                            if (prev == null || compareStreamId(maxId, prev) > 0) {
                                frontier.put(group, maxId);
                            }
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
        }
    }

    private static StreamMessageId parseStreamId(String id) {
        try {
            if (id == null) return StreamMessageId.MIN;
            String[] parts = id.split("-", 2);
            if (parts.length == 2) {
                long ms = Long.parseLong(parts[0]);
                long seq = Long.parseLong(parts[1]);
                return new StreamMessageId(ms, seq);
            }
            long ms = Long.parseLong(id);
            return new StreamMessageId(ms);
        } catch (Exception e) {
            return StreamMessageId.MIN;
        }
    }

    private static int compareStreamId(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        try {
            String[] ap = a.split("-", 2);
            String[] bp = b.split("-", 2);
            long ams = Long.parseLong(ap[0]);
            long bms = Long.parseLong(bp[0]);
            int c = Long.compare(ams, bms);
            if (c != 0) return c;
            long ase = ap.length > 1 ? Long.parseLong(ap[1]) : 0L;
            long bse = bp.length > 1 ? Long.parseLong(bp[1]) : 0L;
            return Long.compare(ase, bse);
        } catch (Exception ignore) {
            return a.compareTo(b);
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
                String frontierKey = StreamKeys.commitFrontier(topic, pid);
                String committedId = null;
                try {
                    @SuppressWarnings("rawtypes")
                    RMap frontier = redissonClient.getMap(frontierKey);
                    Object v = frontier.get(consumerGroup);
                    committedId = v == null ? null : String.valueOf(v);
                } catch (Exception ignore) {
                }
                String startId = (committedId == null || committedId.isBlank()) ? "0-0" : committedId;
                String streamKey = StreamKeys.partitionStream(topic, pid);
                try {
                    Object r = script.eval(RScript.Mode.READ_WRITE, ensureGroupLua, RScript.ReturnType.STATUS,
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

    private void annotateRuntimeError(Message message, String consumerGroup, Throwable error) {
        if (message == null) {
            return;
        }
        try {
            java.util.Map<String, String> headers = message.getHeaders();
            if (headers == null) {
                headers = new java.util.HashMap<>();
                message.setHeaders(headers);
            } else if (!(headers instanceof java.util.HashMap)) {
                headers = new java.util.HashMap<>(headers);
                message.setHeaders(headers);
            }
            headers.put(RedisRuntimeHeaders.JOB_NAME, config.getJobName());
            headers.put(RedisRuntimeHeaders.CONSUMER_GROUP, consumerGroup);
            if (error != null) {
                headers.put(RedisRuntimeHeaders.ERROR_TYPE, error.getClass().getName());
                String msg = error.getMessage();
                if (msg != null) {
                    headers.put(RedisRuntimeHeaders.ERROR_MESSAGE, truncate(msg, 512));
                }
            }
        } catch (Exception ignore) {
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (maxLen <= 0) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private void registerStreamId(String streamId) {
        if (!usedStreamIds.add(streamId)) {
            throw new IllegalStateException("Duplicate sourceId/streamId: " + streamId);
        }
    }

    private static String requireValidId(String id) {
        if (id == null) {
            throw new NullPointerException("sourceId");
        }
        String v = id.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (!ID_PATTERN.matcher(v).matches()) {
            throw new IllegalArgumentException("sourceId must match " + ID_PATTERN.pattern() + ", but got: " + id);
        }
        return v;
    }
}
