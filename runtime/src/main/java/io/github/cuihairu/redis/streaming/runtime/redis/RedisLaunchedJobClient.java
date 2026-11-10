package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageQueueFactory;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.CheckpointGate;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.JobControlGate;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisLeaderElector;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipeline;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineDefinition;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisRuntimeCheckpointManager;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handle for a job started by {@link RedisStreamExecutionEnvironment#executeAsync()}:
 * cancellation, manual checkpoint triggering, pause/resume, live diagnostics and the
 * dynamic-parallelism (scale up/down) flow.
 */
final class RedisLaunchedJobClient implements RedisJobClient {
    private static final Logger log = LoggerFactory.getLogger(RedisLaunchedJobClient.class);

    private final RedisRuntimeConfig config;
    private final List<RedisPipelineDefinition> pipelineDefinitions;
    private final MessageQueueFactory mqFactory;
    private final AtomicBoolean executed;
    private final RedisCheckpointFlow checkpointFlow;
    private final RedisSubtaskLauncher subtaskLauncher;

    private final AtomicReference<ScheduledExecutorService> checkpointExecutorRef;
    private final ScheduledExecutorService leaderRenewExecutor;
    private final RedisLeaderElector leaderElector;
    private final AtomicBoolean isLeader;
    private final ScheduledExecutorService sharedTimerExecutor;
    private final List<MessageConsumer> consumers;
    private final List<RedisPipelineRunner<?>> runners;
    private final List<Subtask> subtasks;
    private final AtomicLong consumerSeq;
    private final RedisRuntimeCheckpointManager checkpointManager;
    private final List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys;
    private final DeferredAcks deferredAcks;
    private final TwoPhaseEpochTracker twoPhaseEpochs;
    private final CheckpointGate gate;
    private final JobControlGate jobControlGate;
    private final java.util.concurrent.atomic.AtomicReference<Long> restoredCheckpointId;

    private RedisLaunchedJobClient(AtomicReference<ScheduledExecutorService> checkpointExecutorRef,
                                  ScheduledExecutorService leaderRenewExecutor,
                                  RedisLeaderElector leaderElector,
                                  AtomicBoolean isLeader,
                                  ScheduledExecutorService sharedTimerExecutor,
                                  List<MessageConsumer> consumers,
                                  List<RedisPipelineRunner<?>> runners,
                                  List<Subtask> subtasks,
                                  AtomicLong consumerSeq,
                                  RedisRuntimeCheckpointManager checkpointManager,
                                  List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys,
                                  DeferredAcks deferredAcks,
                                  TwoPhaseEpochTracker twoPhaseEpochs,
                                  CheckpointGate gate,
                                  JobControlGate jobControlGate,
                                  java.util.concurrent.atomic.AtomicReference<Long> restoredCheckpointId,
                                  RedisRuntimeConfig config,
                                  List<RedisPipelineDefinition> pipelineDefinitions,
                                  MessageQueueFactory mqFactory,
                                  AtomicBoolean executed,
                                  RedisCheckpointFlow checkpointFlow,
                                  RedisSubtaskLauncher subtaskLauncher) {
        this.config = config;
        this.pipelineDefinitions = pipelineDefinitions;
        this.mqFactory = mqFactory;
        this.executed = executed;
        this.checkpointFlow = checkpointFlow;
        this.subtaskLauncher = subtaskLauncher;
        this.checkpointExecutorRef = checkpointExecutorRef;
        this.leaderRenewExecutor = leaderRenewExecutor;
        this.leaderElector = leaderElector;
        this.isLeader = isLeader;
        this.sharedTimerExecutor = sharedTimerExecutor;
        this.consumers = consumers;
        this.runners = runners;
        this.subtasks = subtasks;
        this.consumerSeq = consumerSeq;
        this.checkpointManager = checkpointManager;
        this.pipelineKeys = pipelineKeys;
        this.deferredAcks = deferredAcks;
        this.twoPhaseEpochs = twoPhaseEpochs;
        this.gate = gate;
        this.jobControlGate = jobControlGate;
        this.restoredCheckpointId = restoredCheckpointId;
    }

    static RedisLaunchedJobClient create(AtomicReference<ScheduledExecutorService> checkpointExecutorRef,
                                         ScheduledExecutorService leaderRenewExecutor,
                                         RedisLeaderElector leaderElector,
                                         AtomicBoolean isLeader,
                                         ScheduledExecutorService sharedTimerExecutor,
                                         List<MessageConsumer> consumers,
                                         List<RedisPipelineRunner<?>> runners,
                                         List<Subtask> subtasks,
                                         AtomicLong consumerSeq,
                                         RedisRuntimeCheckpointManager checkpointManager,
                                         List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys,
                                         DeferredAcks deferredAcks,
                                         TwoPhaseEpochTracker twoPhaseEpochs,
                                         CheckpointGate gate,
                                         JobControlGate jobControlGate,
                                         java.util.concurrent.atomic.AtomicReference<Long> restoredCheckpointId,
                                         RedisRuntimeConfig config,
                                         List<RedisPipelineDefinition> pipelineDefinitions,
                                         MessageQueueFactory mqFactory,
                                         AtomicBoolean executed,
                                         RedisCheckpointFlow checkpointFlow,
                                         RedisSubtaskLauncher subtaskLauncher) {
        return new RedisLaunchedJobClient(checkpointExecutorRef, leaderRenewExecutor, leaderElector, isLeader,
                sharedTimerExecutor, consumers, runners, subtasks, consumerSeq,
                checkpointManager, pipelineKeys, deferredAcks, twoPhaseEpochs, gate, jobControlGate,
                restoredCheckpointId, config, pipelineDefinitions, mqFactory, executed,
                checkpointFlow, subtaskLauncher);
    }

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
            QuietClose.stopConsumersQuietly(consumers);
            QuietClose.closeRunnersQuietly(runners);
            QuietClose.shutdownExecutorQuietly(sharedTimerExecutor);
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
        if (!gate.tryBeginAlignedCheckpoint()) {
            return null;
        }
        try {
            return checkpointFlow.triggerCheckpointInternal(consumers, runners, checkpointManager, pipelineKeys,
                    deferredAcks, twoPhaseEpochs);
        } finally {
            gate.endAlignedCheckpoint();
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
        public boolean scaleParallelism(int newParallelism) {
            if (newParallelism < 1) {
                throw new IllegalArgumentException("newParallelism must be >= 1");
            }
            if (canceled.get()) {
                return false;
            }
            // exclusive with checkpoint flows (they pause the consumers and iterate
            // the same subtask lists): wait out an in-flight checkpoint instead of
            // racing it on the list mutations
            long deadline = System.currentTimeMillis() + 30_000;
            while (!gate.tryBeginScale()) {
                if (canceled.get() || System.currentTimeMillis() > deadline) {
                    return false;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            try {
                // cross-instance counterpart: no two clients may re-balance the same
                // subtask lists, so the lease is won before any list is touched. A
                // null gate is "not configured", not a refusal.
                if (jobControlGate != null && !tryBeginJobControlGate()) {
                    return false;
                }
                try {
                    return scaleSubtasks(newParallelism);
                } finally {
                    if (jobControlGate != null) {
                        jobControlGate.endControlOp();
                    }
                }
            } finally {
                gate.endScale();
            }
        }

        private boolean tryBeginJobControlGate() {
            return jobControlGate != null
                    && jobControlGate.tryBeginControlOp(Math.max(0L, config.getControlGateWait().toMillis()));
        }

        private boolean scaleSubtasks(int newParallelism) {
            // one subtask unit per pipeline, not per checkpoint key: a dual-source
            // pipeline contributes two (topic, group) keys but is still one chain
            int pipelines = Math.max(1, pipelineDefinitions.size());
            List<List<Subtask>> byPipeline = new ArrayList<>(pipelines);
            for (int i = 0; i < pipelines; i++) {
                byPipeline.add(new ArrayList<>());
            }
            for (Subtask st : subtasks) {
                if (st.pipelineIndex < byPipeline.size()) {
                    byPipeline.get(st.pipelineIndex).add(st);
                }
            }
            int current = byPipeline.get(0).size();
            if (newParallelism == current) {
                return true;
            }
            try {
                if (newParallelism < current) {
                    // repin the survivors first: partitions moving between survivors
                    // hand over promptly; partitions still held by the removed
                    // subtasks follow when those stop below
                    for (List<Subtask> pl : byPipeline) {
                        for (int i = 0; i < newParallelism && i < pl.size(); i++) {
                            repin(pl.get(i), newParallelism, i);
                        }
                    }
                    for (List<Subtask> pl : byPipeline) {
                        for (int i = newParallelism; i < pl.size(); i++) {
                            removeSubtask(pl.get(i));
                        }
                    }
                } else {
                    for (List<Subtask> pl : byPipeline) {
                        for (int i = 0; i < pl.size(); i++) {
                            repin(pl.get(i), newParallelism, i);
                        }
                    }
                    for (int pi = 0; pi < pipelines && pi < pipelineDefinitions.size(); pi++) {
                        RedisPipeline<?> p = pipelineDefinitions.get(pi).freeze();
                        for (int i = current; i < newParallelism; i++) {
                            if (canceled.get()) {
                                return false;
                            }
                            addSubtaskForScale(p, pi, i, newParallelism);
                        }
                    }
                }
                log.info("Scaled job {} parallelism {} -> {} (instance {})",
                        config.getJobName(), current, newParallelism, config.getJobInstanceId());
                return true;
            } catch (Exception e) {
                log.error("scaleParallelism({}) failed (jobName={}, from={})",
                        newParallelism, config.getJobName(), current, e);
                return false;
            }
        }

        private void repin(Subtask st, int parallelism, int subtask) {
            if (st.consumer instanceof io.github.cuihairu.redis.streaming.mq.control.ReassignableMessageConsumer rmc) {
                // every source of the pipeline keeps the same modulo/remainder, so a
                // dual-source subtask stays pinned consistently on both topics
                RedisPipelineDefinition def = pipelineDefinitions.get(st.pipelineIndex);
                for (int s = 0; s < def.sourceCount(); s++) {
                    rmc.updatePartitionAssignment(def.topicAt(s), parallelism, subtask);
                }
            }
        }

        private void removeSubtask(Subtask st) {
            subtasks.remove(st);
            runners.remove(st.runner);
            consumers.remove(st.consumer);
            // stop first so the workers release their leases (prompt handover). The
            // runner is intentionally NOT closed: sinks are shared across a
            // pipeline's runners and stay owned by the survivors; cancel() closes
            // them at job end. A removed runner is passive (no threads) — at most a
            // bounded tail of already-scheduled timers still fires into shared,
            // partition-keyed state.
            try {
                st.consumer.stop();
            } catch (Exception ignore) {
            }
            try {
                st.consumer.close();
            } catch (Exception ignore) {
            }
        }

        private void addSubtaskForScale(RedisPipeline<?> p, int pipelineIndex, int subtask, int parallelism) {
            RedisPipelineRunner<?> runner = p.buildRunner(sharedTimerExecutor);
            runners.add(runner);
            String consumerName = config.getJobName() + "-" + config.getJobInstanceId() + "-"
                    + consumerSeq.incrementAndGet();
            MessageConsumer consumer = mqFactory.createConsumer(consumerName);
            consumers.add(consumer);
            subtaskLauncher.startSubtaskConsumer(consumer, consumerName, p, subtask, parallelism, runner, deferredAcks);
            subtasks.add(new Subtask(pipelineIndex, runner, consumer));
        }

    @Override
            public Map<String, Object> diagnostics() {
                Map<String, Object> out = new HashMap<>();
        out.put("jobName", config.getJobName());
        out.put("jobInstanceId", config.getJobInstanceId());
        out.put("pipelineParallelism", config.getPipelineParallelism());
        out.put("timerThreads", config.getTimerThreads());
        out.put("checkpointThreads", config.getCheckpointThreads());
        out.put("deferAckUntilCheckpoint", config.isDeferAckUntilCheckpoint());
        out.put("ackDeferredMessagesOnCheckpoint", config.isAckDeferredMessagesOnCheckpoint());
        out.put("checkpointIntervalMs", config.getCheckpointInterval() == null ? 0L : Math.max(0L, config.getCheckpointInterval().toMillis()));
        out.put("restoreFromLatestCheckpoint", config.isRestoreFromLatestCheckpoint());
        out.put("sinkDeduplicationEnabled", config.isSinkDeduplicationEnabled());
        out.put("sinkDeduplicationTtlMs", config.getSinkDeduplicationTtl() == null ? 0L : Math.max(0L, config.getSinkDeduplicationTtl().toMillis()));
        out.put("watermarkOutOfOrdernessMs", config.getWatermarkOutOfOrderness() == null ? 0L : Math.max(0L, config.getWatermarkOutOfOrderness().toMillis()));
        out.put("windowAllowedLatenessMs", config.getWindowAllowedLateness() == null ? 0L : Math.max(0L, config.getWindowAllowedLateness().toMillis()));
        out.put("eventTimeTimerMaxSize", config.getEventTimeTimerMaxSize());
        out.put("consumerCount", consumers.size());
        // parallelism is live after scaleParallelism; the config value is the start-time setting
        // (pipeline definitions, not checkpoint keys — a dual-source pipeline contributes
        // two keys but is still one subtask unit)
        out.put("liveParallelism", subtasks.isEmpty() || pipelineDefinitions.isEmpty()
                ? config.getPipelineParallelism()
                : Math.max(1, subtasks.size() / pipelineDefinitions.size()));
                out.put("runnerCount", runners.size());
                out.put("inFlight", inFlight());
                out.put("checkpointing", gate.isExclusive());
                out.put("fullSilence", gate.isFullSilence());
                out.put("restoredCheckpointId", restoredCheckpointId.get());
        out.put("leaderElectionEnabled", config.isLeaderElectionEnabled());
        if (leaderElector != null) {
            out.put("isLeader", isLeader.get());
            out.put("leaderInstanceId", leaderElector.currentLeader());
            out.put("fencingToken", leaderElector.currentFencingToken());
        }
                try {
                    Checkpoint latest = checkpointManager.getLatestCheckpoint();
                    out.put("latestCheckpointId", latest == null ? null : latest.getCheckpointId());
                } catch (Exception ignore) {
        }
        try {
            Checkpoint latestCommitted = checkpointManager.getLatestSinkCommittedCheckpoint();
            out.put("latestSinkCommittedCheckpointId", latestCommitted == null ? null : latestCommitted.getCheckpointId());
        } catch (Exception ignore) {
        }
        List<Map<String, Object>> pipelines = new ArrayList<>();
        for (RedisRuntimeCheckpointManager.PipelineKey pk : pipelineKeys) {
            Map<String, Object> p = new HashMap<>();
            p.put("topic", pk.topic());
            p.put("consumerGroup", pk.consumerGroup());
            pipelines.add(p);
        }
        out.put("pipelines", pipelines);
        return out;
    }

        @Override
        public boolean awaitTermination(Duration timeout) throws InterruptedException {
            Duration t = timeout == null ? Duration.ZERO : timeout;
            return stopped.await(Math.max(0, t.toMillis()), TimeUnit.MILLISECONDS);
        }
}
