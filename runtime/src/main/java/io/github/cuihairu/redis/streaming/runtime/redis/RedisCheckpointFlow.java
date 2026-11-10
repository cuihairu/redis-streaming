package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.control.PausableMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.JobControlGate;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisPipelineRunner;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisRuntimeCheckpointManager;
import io.github.cuihairu.redis.streaming.runtime.redis.metrics.RedisRuntimeMetrics;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The aligned checkpoint flow of the Redis runtime: pausing drain, per-record barriers,
 * two-phase-commit prepare/store/commit, and the recovery compensation that replays or
 * discards stored transaction handles after a restore. Serialized externally by the
 * in-process {@code CheckpointGate} and the cross-instance {@link JobControlGate}.
 */
final class RedisCheckpointFlow {
    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointFlow.class);

    private final RedisRuntimeConfig config;
    private final RedissonClient redissonClient;
    /** Null when the cross-instance gate is disabled; null is "not configured", not a refusal. */
    private final JobControlGate jobControlGate;

    RedisCheckpointFlow(RedisRuntimeConfig config, RedissonClient redissonClient, JobControlGate jobControlGate) {
        this.config = config;
        this.redissonClient = redissonClient;
        this.jobControlGate = jobControlGate;
    }

    /** Tenant-aware MQ key view for this environment (derived from the configured MQ options). */
    private StreamKeys keys() {
        return StreamKeys.of(config.getMqOptions());
    }

    Checkpoint triggerCheckpointInternal(List<MessageConsumer> consumers,
                                                 List<RedisPipelineRunner<?>> runners,
                                                 RedisRuntimeCheckpointManager checkpointManager,
                                                 List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys,
                                                 DeferredAcks deferredAcks,
                                                 TwoPhaseEpochTracker twoPhaseEpochs) {
        // A null gate is "not configured" — it must not read as a refusal. Refusal and
        // "not configured" are deliberately separate checks at every gate call site.
        if (jobControlGate != null && !tryBeginJobControlGate()) {
            return null;
        }
        try {
            Checkpoint cp = triggerCheckpointWhilePaused(consumers, runners, checkpointManager, pipelineKeys,
                    deferredAcks, twoPhaseEpochs);
            if (cp != null) {
                // RT-H2: the retention sweep lists and fully deserializes every retained
                // checkpoint. It used to run inside the paused window below, stretching the
                // stop-the-world pause on every tick by the size of the whole checkpoint
                // history — run it now that the finally block has resumed the consumers.
                checkpointManager.cleanupOld();
            }
            return cp;
        } finally {
            if (jobControlGate != null) {
                jobControlGate.endControlOp();
            }
        }
    }

    /**
     * Try to win the cross-instance control lease, bounded by the configured wait. Tolerates a
     * null (disabled) gate by returning {@code false} too — which is why every call site must
     * branch on {@code jobControlGate != null} first: a {@code false} after that check is a
     * refusal, and it must never be mixed up with the null-gate "not configured" case.
     */
    private boolean tryBeginJobControlGate() {
        return jobControlGate != null
                && jobControlGate.tryBeginControlOp(Math.max(0L, config.getControlGateWait().toMillis()));
    }

    /**
     * Best-effort abort of every open two-phase-commit epoch; failures are logged, not
     * propagated. When an earlier checkpoint already stored this epoch's handles, that
     * checkpoint is marked aborted so a later restore skips it — its offsets must not be
     * adopted, because the data they refer to was just discarded.
     */
    private void abortTwoPhaseQuietly(List<RedisPipelineRunner<?>> runners,
                                      RedisRuntimeCheckpointManager checkpointManager,
                                      TwoPhaseEpochTracker twoPhaseEpochs) {
        for (RedisPipelineRunner<?> r : runners) {
            try {
                r.abortTwoPhaseCommits();
            } catch (Exception e) {
                log.warn("Two-phase abort failed (jobName={})", config.getJobName(), e);
            }
        }
        Long durable = twoPhaseEpochs.takeDurable();
        if (durable != null && checkpointManager.markTxnEpochAborted(durable)) {
            log.info("Marked checkpoint {} as holding a discarded two-phase epoch (jobName={})",
                    durable, config.getJobName());
        }
    }

    /**
     * Two-phase-commit recovery compensation after a restore. When the restored checkpoint
     * was never marked sink-committed (crash between the checkpoint store and the sink
     * commit phase), every stored transaction handle of this runner is replayed through
     * {@code recoverAndCommit} — the sink contract makes that idempotent, so sinks that did
     * commit before dying tolerate the replay. Handles of an already sink-committed
     * checkpoint are stale and skipped: their data is visible, fresh epochs begin new
     * transactions. Returns whether this runner replayed anything, so the caller can mark
     * the checkpoint committed only after every runner finished.
     */
    boolean recoverTwoPhaseTransactions(RedisPipelineRunner<?> runner,
                                             int runnerIndex,
                                             RedisRuntimeCheckpointManager checkpointManager,
                                             Checkpoint restored) {
        try {
            Map<String, String> handles = checkpointManager.getTxnHandles(restored);
            if (handles.isEmpty()) {
                return false;
            }
            boolean sinkCommitted = checkpointManager.isSinkCommittedMarkerPresent(restored.getCheckpointId());
            if (sinkCommitted) {
                return false;
            }
            String prefix = runnerIndex + ":";
            boolean replayedAny = false;
            for (Map.Entry<String, String> e : handles.entrySet()) {
                if (!e.getKey().startsWith(prefix)) {
                    continue;
                }
                int sinkIndex = Integer.parseInt(e.getKey().substring(prefix.length()));
                runner.recoverTwoPhaseCommit(sinkIndex, e.getValue());
                replayedAny = true;
                log.info("Recovered two-phase-commit transaction for runner {} sink {} from checkpoint {} (jobName={})",
                        runnerIndex, sinkIndex, restored.getCheckpointId(), config.getJobName());
            }
            return replayedAny;
        } catch (Exception e) {
            // matches the restore path's tolerance: a broken compensation is surfaced in the
            // logs but does not prevent the job from starting
            log.error("Two-phase-commit recovery failed (jobName={}, checkpointId={})",
                    config.getJobName(), restored.getCheckpointId(), e);
        }
        return false;
    }

    /**
     * Once every runner of the restored checkpoint has replayed its transaction handles, the
     * staged data is visible again, so the checkpoint is genuinely sink-committed: marking
     * it keeps a later restore from replaying the same handles a second time and lets the
     * normal restore path pick it up. Only the last runner that actually replayed something
     * triggers this, and only if no runner failed.
     */
    void markRecoveredCheckpointCommitted(RedisRuntimeCheckpointManager checkpointManager,
                                                  Checkpoint restored) {
        if (checkpointManager.markSinkCommitted(restored)) {
            log.info("Marked recovered checkpoint {} sink-committed after replaying its transactions (jobName={})",
                    restored.getCheckpointId(), config.getJobName());
        } else {
            log.warn("Could not mark recovered checkpoint {} sink-committed; its transactions will be "
                    + "replayed again on the next restore (jobName={})", restored.getCheckpointId(), config.getJobName());
        }
    }

    private Checkpoint triggerCheckpointWhilePaused(List<MessageConsumer> consumers,
                                                    List<RedisPipelineRunner<?>> runners,
                                                    RedisRuntimeCheckpointManager checkpointManager,
                                                    List<RedisRuntimeCheckpointManager.PipelineKey> pipelineKeys,
                                                    DeferredAcks deferredAcks,
                                                    TwoPhaseEpochTracker twoPhaseEpochs) {
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
        long overallStartNs = System.nanoTime();
        try {
            RedisRuntimeMetrics.get().incCheckpointTriggered(config.getJobName());
        } catch (Exception ignore) {
        }
        try {
            Duration drainTimeout = config.getCheckpointDrainTimeout();
            long deadline = System.currentTimeMillis() + Math.max(0, drainTimeout == null ? 0 : drainTimeout.toMillis());
            long drainStartNs = System.nanoTime();
            while (true) {
                long total = 0;
                    for (MessageConsumer c : consumers) {
                        // barrier view: handlers plus reads whose fetched messages have not
                        // reached handler accounting yet — draining the handler count alone
                        // let a fetched batch be invoked inside the paused window
                        total += ((PausableMessageConsumer) c).inFlightBarrier();
                    }
                    if (total <= 0) {
                        break;
                    }
                    if (System.currentTimeMillis() > deadline) {
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
            try {
                RedisRuntimeMetrics.get().recordCheckpointDrainDuration(config.getJobName(), (System.nanoTime() - drainStartNs) / 1_000_000);
            } catch (Exception ignore) {
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
            Map<RedisRuntimeCheckpointManager.PipelineKey, Map<Integer, java.util.List<String>>> deferredAckSnapshot = null;
            if (config.isDeferAckUntilCheckpoint()) {
                offsetsOverride = deferredAcks.snapshotOffsets();
                // Freeze the ack set now: ids recorded after this point (a read that raced
                // the paused window) belong to the NEXT epoch, so this checkpoint must not
                // ack them on success — its commit only covers the prepared snapshot.
                deferredAckSnapshot = deferredAcks.drainForAck();
            }

            // Two-phase-commit phase 1: pre-commit every 2PC sink and collect the encoded
            // transaction handles ("runnerIndex:sinkIndex" -> handle). They go into the
            // checkpoint BEFORE anything commits so recovery can compensate a crash in
            // between. Keys are stable because runners are built in a deterministic
            // (definition, subtask) order.
            Map<String, String> txnHandles = new java.util.LinkedHashMap<>();
            for (int ri = 0; ri < runners.size(); ri++) {
                RedisPipelineRunner<?> r = runners.get(ri);
                try {
                    for (Map.Entry<Integer, String> e : r.prepareTwoPhaseCommits().entrySet()) {
                        txnHandles.put(ri + ":" + e.getKey(), e.getValue());
                    }
                } catch (Exception e) {
                    log.error("Two-phase preCommit failed (jobName={}, checkpointId={})", config.getJobName(), checkpointId, e);
                    abortTwoPhaseQuietly(runners, checkpointManager, twoPhaseEpochs);
                    // The aborted epoch's side effects were discarded, so the records it
                    // consumed must come back through pending-entry reclaim. Drop the
                    // deferred tracking without acking: keeping the ids would let the NEXT
                    // successful checkpoint claim them via its offsets override and ackAll
                    // even though no committed epoch holds their data — silent loss. Leaving
                    // them pending keeps the frontier honest until they are reprocessed.
                    if (config.isDeferAckUntilCheckpoint()) {
                        deferredAcks.clear();
                    }
                    for (RedisPipelineRunner<?> rr : runners) {
                        rr.onCheckpointAbort(checkpointId, e);
                    }
                    try {
                        RedisRuntimeMetrics.get().incCheckpointFailed(config.getJobName());
                    } catch (Exception ignore) {
                    }
                    return null;
                }
            }

            long storeStartNs = System.nanoTime();
            // RT-H2: the retention sweep is deferred to after the consumers are resumed
            Checkpoint cp = checkpointManager.triggerCheckpoint(checkpointId, pipelineKeys, offsetsOverride, false,
                    txnHandles.isEmpty() ? null : txnHandles);
            try {
                RedisRuntimeMetrics.get().recordCheckpointStoreDuration(config.getJobName(), (System.nanoTime() - storeStartNs) / 1_000_000);
            } catch (Exception ignore) {
            }
            if (cp != null) {
                // the epoch's handles are now durable in this checkpoint: it becomes the
                // in-doubt restore point until the sink commit below succeeds
                twoPhaseEpochs.markDurable(checkpointId);
            }
            if (cp == null) {
                // nothing was stored: the pre-committed transactions must never become visible
                abortTwoPhaseQuietly(runners, checkpointManager, twoPhaseEpochs);
                // same rationale as the preCommit-failure path above: the aborted epoch's
                // records must come back via redelivery, so stop tracking them as deferred —
                // otherwise the next successful checkpoint acks and advances the frontier
                // over records whose side effects this abort just discarded
                if (config.isDeferAckUntilCheckpoint()) {
                    deferredAcks.clear();
                }
                for (RedisPipelineRunner<?> r : runners) {
                    r.onCheckpointAbort(checkpointId, null);
                }
                try {
                    RedisRuntimeMetrics.get().incCheckpointFailed(config.getJobName());
                } catch (Exception ignore) {
                }
                return null;
            }

            try {
                long sinkCommitStartNs = System.nanoTime();
                for (RedisPipelineRunner<?> r : runners) {
                    r.onCheckpointComplete(checkpointId);
                }
                // Two-phase-commit phase 2: the handles are durably stored, so the sinks may
                // finalize now. If a commit throws, the checkpoint stays NOT sink-committed
                // and recovery replays recoverAndCommit from the stored handles.
                for (RedisPipelineRunner<?> r : runners) {
                    r.commitTwoPhaseCommits();
                }
                checkpointManager.markSinkCommitted(cp);
                // the epoch is finalized and its handles consumed: nothing stays in doubt
                twoPhaseEpochs.clearDurable();
                try {
                    RedisRuntimeMetrics.get().recordCheckpointSinkCommitDuration(config.getJobName(), (System.nanoTime() - sinkCommitStartNs) / 1_000_000);
                } catch (Exception ignore) {
                }
            } catch (Exception e) {
                log.error("Sink commit on checkpoint failed (jobName={}, checkpointId={})", config.getJobName(), checkpointId, e);
                // the 2PC epochs are deliberately NOT aborted here: their handles are already
                // in the durable checkpoint, so discarding them would drop data that the
                // recovery path is obliged to commit. The checkpoint stays unmarked and the
                // deferred acks below are skipped, so the records come back and recovery
                // replays recoverAndCommit from the stored handles (idempotent by contract).
                // The checkpoint stays flagged as the in-doubt epoch (markDurable above), so
                // a later failure that DOES abort the epoch invalidates exactly this
                // checkpoint instead of leaving an adoptable-but-dead restore point.
                for (RedisPipelineRunner<?> r : runners) {
                    r.onCheckpointAbort(checkpointId, e);
                }
                try {
                    RedisRuntimeMetrics.get().incCheckpointFailed(config.getJobName());
                } catch (Exception ignore) {
                }
                return cp;
            }

            if (config.isDeferAckUntilCheckpoint()) {
                if (config.isAckDeferredMessagesOnCheckpoint()) {
                    deferredAcks.ackAll(redissonClient, deferredAckSnapshot, keys());
                } else {
                    deferredAcks.clear();
                }
            }
            try {
                RedisRuntimeMetrics.get().incCheckpointCompleted(config.getJobName());
            } catch (Exception ignore) {
            }
            return cp;
        } finally {
            try {
                RedisRuntimeMetrics.get().recordCheckpointDuration(config.getJobName(), (System.nanoTime() - overallStartNs) / 1_000_000);
            } catch (Exception ignore) {
            }
            for (MessageConsumer c : consumers) {
                if (c instanceof PausableMessageConsumer pc) {
                    try {
                        pc.resume();
                    } catch (Exception e) {
                        log.warn("Failed to resume consumer after checkpoint (jobName={})", config.getJobName(), e);
                    }
                }
            }
        }
    }
}
