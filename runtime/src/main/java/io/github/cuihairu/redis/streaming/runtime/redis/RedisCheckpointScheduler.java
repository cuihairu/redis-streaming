package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.CheckpointGate;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisLeaderElector;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisRuntimeCheckpointManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodic checkpoint scheduling and leader coordination for the Redis runtime. With
 * leader election disabled the checkpoint scheduler runs on every instance (previous
 * behavior); with election enabled only the leader checkpoints periodically — two leaders
 * running independent checkpoint schedules would allocate colliding checkpoint ids
 * (split-brain double-write) — and the renew loop takes over checkpointing when the
 * leader is lost.
 */
final class RedisCheckpointScheduler {
    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointScheduler.class);

    private final RedisRuntimeConfig config;
    private final RedisCheckpointFlow checkpointFlow;

    RedisCheckpointScheduler(RedisRuntimeConfig config, RedisCheckpointFlow checkpointFlow) {
        this.config = config;
        this.checkpointFlow = checkpointFlow;
    }

    /**
     * Start the periodic checkpoint scheduler. With leader election disabled this runs on
     * every instance (previous behavior); with election enabled it runs only on the leader.
     */
    void startCheckpointExecutor(AtomicReference<ScheduledExecutorService> checkpointExecutorRef,
                                         List<MessageConsumer> consumers,
                                         List<RedisPipelineRunner<?>> runners,
                                         RedisRuntimeCheckpointManager checkpointManager,
                                         List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys,
                                         DeferredAcks deferredAcks,
                                         TwoPhaseEpochTracker twoPhaseEpochs,
                                         CheckpointGate gate) {
        Duration interval = config.getCheckpointInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return;
        }
        int threads = Math.max(1, config.getCheckpointThreads());
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(threads);
        ex.setRemoveOnCancelPolicy(true);
        checkpointExecutorRef.set(ex);
        long ms = Math.max(50L, interval.toMillis());
        ex.scheduleWithFixedDelay(() -> {
            if (!gate.tryBeginAlignedCheckpoint()) {
                return;
            }
            try {
                checkpointFlow.triggerCheckpointInternal(consumers, runners, checkpointManager, pipelineKeys,
                        deferredAcks, twoPhaseEpochs);
            } catch (Exception e) {
                log.debug("Periodic checkpoint failed (jobName={})", config.getJobName(), e);
            } finally {
                gate.endAlignedCheckpoint();
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }

    void stopCheckpointExecutor(AtomicReference<ScheduledExecutorService> checkpointExecutorRef) {
        QuietClose.shutdownExecutorQuietly(checkpointExecutorRef.getAndSet(null));
    }

    /**
     * Leader coordination loop: renew the lease while leading, take over when the leader
     * is lost, and start/stop the periodic checkpoint scheduler accordingly.
     */
    ScheduledExecutorService startLeaderCoordination(AtomicReference<ScheduledExecutorService> checkpointExecutorRef,
                                         List<MessageConsumer> consumers,
                                         List<RedisPipelineRunner<?>> runners,
                                         RedisRuntimeCheckpointManager checkpointManager,
                                         List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys,
                                         DeferredAcks deferredAcks,
                                         TwoPhaseEpochTracker twoPhaseEpochs,
                                         CheckpointGate gate,
                                         AtomicBoolean isLeader,
                                         RedisLeaderElector elector) {
        long renewMs = Math.max(1000L, config.getLeaderRenewInterval().toMillis());
        ScheduledThreadPoolExecutor renewEx = new ScheduledThreadPoolExecutor(1);
        renewEx.setRemoveOnCancelPolicy(true);
        renewEx.scheduleWithFixedDelay(() -> {
            try {
                if (isLeader.get()) {
                    if (!elector.renewLeadership()) {
                        isLeader.set(false);
                        log.warn("Lost leadership for job {} (instance {}); periodic checkpointing stopped",
                                config.getJobName(), config.getJobInstanceId());
                        stopCheckpointExecutor(checkpointExecutorRef);
                    }
                } else if (elector.tryAcquireLeadership()) {
                    isLeader.set(true);
                    long token = elector.nextFencingToken();
                    // HA: the dead leader may have written checkpoints after this instance's
                    // startup snapshot — re-align before allocating any id as the new leader
                    checkpointManager.refreshCheckpointIdFromStorage();
                    log.info("Acquired leadership for job {} (instance {}, fencingToken={})",
                            config.getJobName(), config.getJobInstanceId(), token);
                    startCheckpointExecutor(checkpointExecutorRef, consumers, runners, checkpointManager,
                            pipelineKeys, deferredAcks, twoPhaseEpochs, gate);
                }
            } catch (Exception e) {
                log.debug("Leader coordination tick failed (jobName={})", config.getJobName(), e);
            }
        }, renewMs, renewMs, TimeUnit.MILLISECONDS);
        // already leader at startup: begin checkpointing immediately (the first renew
        // tick is one interval away)
        if (isLeader.get()) {
            startCheckpointExecutor(checkpointExecutorRef, consumers, runners, checkpointManager,
                    pipelineKeys, deferredAcks, twoPhaseEpochs, gate);
        }
        return renewEx;
    }
}
