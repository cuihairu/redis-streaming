package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.RType;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort checkpoint manager for Redis runtime.
 *
 * <p>Stores checkpoints in Redis and supports restoring consumer group offsets and keyed-state hashes.</p>
 */
public final class RedisRuntimeCheckpointManager {
    private static final Logger log = LoggerFactory.getLogger(RedisRuntimeCheckpointManager.class);

    static final String SNAPSHOT_KEY_OFFSETS = "runtime:offsets";
    static final String SNAPSHOT_KEY_STATE = "runtime:state";
    static final String SNAPSHOT_KEY_STATE_SCHEMA = "runtime:stateSchema";
    private static final String SNAPSHOT_KEY_META = "runtime:meta";
    /** Two-phase-commit transaction handles ("runnerIndex:sinkIndex" -> encoded handle). */
    public static final String SNAPSHOT_KEY_TXNS = "runtime:txns";
    private static final String SINK_COMMITTED_MARKER_PREFIX = "runtime:sinkCommitted:";
    private static final String TXN_ABORTED_MARKER_PREFIX = "runtime:txnAborted:";
    /**
     * RT-L5: sink-committed markers previously lived forever — with the documented
     * {@code checkpointsToKeep=0} ("cleanup disabled") they accumulated unbounded. A TTL
     * bounds them; restore cannot lose a commit from expiry because
     * {@code meta.sinkCommitted} inside each retained checkpoint is the authoritative
     * source and is written alongside the marker by {@link #markSinkCommitted}.
     */
    private static final java.time.Duration SINK_COMMITTED_MARKER_TTL = java.time.Duration.ofDays(7);

    public enum RedisStateType {
        MAP,
        ZSET,
        LIST,
        SET
    }

    public static final class RedisStateValue implements Serializable {
        private static final long serialVersionUID = 1L;

        private final RedisStateType type;
        private final Map<String, String> map;
        private final Map<String, Double> zset;
        private final List<String> list;
        private final Set<String> set;

        private RedisStateValue(RedisStateType type, Map<String, String> map, Map<String, Double> zset,
                                List<String> list, Set<String> set) {
            this.type = type;
            this.map = map;
            this.zset = zset;
            this.list = list;
            this.set = set;
        }

        public static RedisStateValue ofMap(Map<String, String> map) {
            return new RedisStateValue(RedisStateType.MAP, map, null, null, null);
        }

        public static RedisStateValue ofZset(Map<String, Double> zset) {
            return new RedisStateValue(RedisStateType.ZSET, null, zset, null, null);
        }

        public static RedisStateValue ofList(List<String> list) {
            return new RedisStateValue(RedisStateType.LIST, null, null, list, null);
        }

        public static RedisStateValue ofSet(Set<String> set) {
            return new RedisStateValue(RedisStateType.SET, null, null, null, set);
        }

        public RedisStateType type() {
            return type;
        }

        public Map<String, String> map() {
            return map;
        }

        public Map<String, Double> zset() {
            return zset;
        }

        public List<String> list() {
            return list;
        }

        public Set<String> set() {
            return set;
        }
    }

    final RedissonClient redissonClient;
    final RedisRuntimeConfig config;
    private final RedisCheckpointStorage storage;
    final TopicPartitionRegistry partitionRegistry;
    private final AtomicLong nextCheckpointId;
    final StreamKeys keys;
    /** Optional leader elector; when present, checkpoints carry a fencing token and restore scans reject stale-token checkpoints. */
    private final RedisLeaderElector leaderElector;
    final RedisRuntimeKeyedStateSnapshotter stateSnapshots = new RedisRuntimeKeyedStateSnapshotter(this);
    final RedisRuntimeOffsetSnapshotter offsetSnapshots = new RedisRuntimeOffsetSnapshotter(this);

    public RedisRuntimeCheckpointManager(RedissonClient redissonClient, RedisRuntimeConfig config) {
        this(redissonClient, config, null);
    }

    public RedisRuntimeCheckpointManager(RedissonClient redissonClient, RedisRuntimeConfig config,
                                         RedisLeaderElector leaderElector) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.config = Objects.requireNonNull(config, "config");
        this.leaderElector = leaderElector;
        String prefix = config.getCheckpointKeyPrefix() + config.getJobName() + ":";
        this.storage = new RedisCheckpointStorage(redissonClient, prefix);
        this.keys = StreamKeys.of(config.getMqOptions());
        this.partitionRegistry = new TopicPartitionRegistry(redissonClient, this.keys);
        this.nextCheckpointId = new AtomicLong(initNextId());
    }

    private long initNextId() {
        try {
            Checkpoint latest = storage.getLatestCheckpoint();
            if (latest != null) {
                return latest.getCheckpointId() + 1;
            }
        } catch (Exception e) {
            log.debug("Failed to init checkpoint id counter from storage", e);
        }
        return 1L;
    }

    public Checkpoint getLatestCheckpoint() {
        try {
            return storage.getLatestCheckpoint();
        } catch (Exception e) {
            log.warn("Failed to read latest checkpoint", e);
            return null;
        }
    }

    public Checkpoint triggerCheckpoint(List<PipelineKey> pipelines) {
        long id = allocateCheckpointId();
        return triggerCheckpoint(id, pipelines, null);
    }

    public long allocateCheckpointId() {
        return nextCheckpointId.getAndIncrement();
    }

    /**
     * HA: re-align the id counter when this instance takes over leadership at runtime.
     * {@link #initNextId()} snapshots storage only at construction, so a follower that has
     * been running while the previous leader kept writing would allocate ids below the
     * dead leader's last checkpoint and overwrite it. Called on every leadership takeover
     * (not at startup: there the constructor's snapshot is already fresh and, having just
     * won the lease, this instance is the only writer). The counter only ever moves
     * forward; a storage outage keeps the local value — the caller has just acquired the
     * lease, so storage was reachable moments ago and a later takeover refreshes again.
     */
    public void refreshCheckpointIdFromStorage() {
        long maxStoredId = 0L;
        try {
            for (Checkpoint c : storage.listCheckpoints(Integer.MAX_VALUE)) {
                if (c != null && c.getCheckpointId() > maxStoredId) {
                    maxStoredId = c.getCheckpointId();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to refresh checkpoint id counter from storage; keeping local counter", e);
            return;
        }
        final long candidate = maxStoredId + 1;
        nextCheckpointId.accumulateAndGet(candidate, Math::max);
    }

    public Checkpoint triggerCheckpoint(long checkpointId,
                                        List<PipelineKey> pipelines,
                                        Map<String, Map<Integer, String>> offsetsOverride) {
        return triggerCheckpoint(checkpointId, pipelines, offsetsOverride, true);
    }

    /**
     * RT-H2: same as {@link #triggerCheckpoint(long, List, Map)} with control over when the
     * retention sweep runs. The sweep lists and fully deserializes every retained checkpoint,
     * so a caller inside a stop-the-world window must pass {@code false} and invoke
     * {@link #cleanupOld()} once it has resumed the consumers — otherwise the pause stretches
     * by the size of the whole checkpoint history on every tick.
     */
    public Checkpoint triggerCheckpoint(long checkpointId,
                                        List<PipelineKey> pipelines,
                                        Map<String, Map<Integer, String>> offsetsOverride,
                                        boolean cleanupRetainedAfterStore) {
        return triggerCheckpoint(checkpointId, pipelines, offsetsOverride, cleanupRetainedAfterStore, null);
    }

    /**
     * Two-phase-commit aware variant: stores the encoded transaction handles
     * ({@code "runnerIndex:sinkIndex" -> handle}) into the snapshot so recovery can
     * compensate with {@code recoverAndCommit}/{@code recoverAndAbort} after a crash
     * between this store and the sink commit phase. Handles must be written
     * <em>before</em> any sink commits; a null/empty map writes no txn key (checkpoints
     * without two-phase-commit sinks stay byte-identical to the plain variant).
     *
     * <p>Leadership gate (2026-10-15): when leader election is enabled, the lease is renewed
     * immediately before the store. An instance that held the lease but can no longer renew
     * it has lost it mid-checkpoint — GC pause, network partition, takeover — and is refused
     * (returns null, nothing written): previously it still landed the snapshot it had already
     * assembled, an orphan the restore side could only reject by fencing token after the
     * fact. Refusing at the store keeps the write inside the term; the allocated checkpoint
     * id is simply skipped (ids stay monotonic). A renewal failure on an instance that never
     * held the lease is a follower — its manual checkpoint keeps the designed path, writing
     * under the shared epoch token (pinned by {@code RedisLeaderElectionIntegrationTest}).
     * Without an elector the path is unchanged.
     */
    public Checkpoint triggerCheckpoint(long checkpointId,
                                        List<PipelineKey> pipelines,
                                        Map<String, Map<Integer, String>> offsetsOverride,
                                        boolean cleanupRetainedAfterStore,
                                        Map<String, String> txnHandles) {
        DefaultCheckpoint cp = new DefaultCheckpoint(checkpointId, System.currentTimeMillis());
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("jobName", config.getJobName());
            meta.put("jobInstanceId", config.getJobInstanceId());
            meta.put("stateKeyPrefix", config.getStateKeyPrefix());
            meta.put("sinkCommitted", Boolean.FALSE);
            if (leaderElector != null) {
                // Fencing token of the current leadership term: restore rejects checkpoints
                // whose token is below the max seen, so a stale leader's in-flight write
                // (landed after losing the lease) is never adopted.
                meta.put("fencingToken", leaderElector.currentFencingToken());
            }
            cp.getStateSnapshot().putState(SNAPSHOT_KEY_META, meta);

            cp.getStateSnapshot().putState(SNAPSHOT_KEY_OFFSETS, offsetSnapshots.snapshotOffsets(pipelines, offsetsOverride));
            cp.getStateSnapshot().putState(SNAPSHOT_KEY_STATE, stateSnapshots.snapshotState());
            cp.getStateSnapshot().putState(SNAPSHOT_KEY_STATE_SCHEMA, stateSnapshots.snapshotStateSchema());
            if (txnHandles != null && !txnHandles.isEmpty()) {
                cp.getStateSnapshot().putState(SNAPSHOT_KEY_TXNS, txnHandles);
            }

            if (leaderElector != null && !leaderElector.renewLeadership()
                    && leaderElector.hasHeldLeadership()) {
                log.warn("Skip checkpoint {}: leadership lease lost before store (jobName={})",
                        checkpointId, config.getJobName());
                return null;
            }
            cp.markCompleted();
            storage.storeCheckpoint(cp);

            if (cleanupRetainedAfterStore) {
                cleanupOld();
            }
            return cp;
        } catch (Exception e) {
            log.warn("Failed to store checkpoint {}", checkpointId, e);
            return null;
        }
    }

    /**
     * Reads the two-phase-commit transaction handles stored in a checkpoint. Returns an
     * empty map when the checkpoint carries none.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getTxnHandles(Checkpoint checkpoint) {
        try {
            Map<String, String> handles = checkpoint.getStateSnapshot().getState(SNAPSHOT_KEY_TXNS);
            return handles == null ? Map.of() : handles;
        } catch (Exception e) {
            log.debug("Failed to read txn handles from checkpoint {}", checkpoint.getCheckpointId(), e);
            return Map.of();
        }
    }

    public boolean markSinkCommitted(Checkpoint checkpoint) {
        if (!(checkpoint instanceof DefaultCheckpoint cp)) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = cp.getStateSnapshot().getState(SNAPSHOT_KEY_META);
            if (meta == null) {
                meta = new HashMap<>();
                meta.put("jobName", config.getJobName());
                meta.put("stateKeyPrefix", config.getStateKeyPrefix());
                cp.getStateSnapshot().putState(SNAPSHOT_KEY_META, meta);
            }
            meta.put("sinkCommitted", Boolean.TRUE);
            storage.storeCheckpoint(cp);
            markSinkCommittedMarker(cp.getCheckpointId());
            return true;
        } catch (Exception e) {
            log.debug("Failed to mark checkpoint sinkCommitted: {}", checkpoint.getCheckpointId(), e);
            return false;
        }
    }

    public boolean markSinkCommittedMarker(long checkpointId) {
        try {
            RBucket<String> b = redissonClient.getBucket(sinkCommittedMarkerKey(checkpointId), StringCodec.INSTANCE);
            b.set("1", SINK_COMMITTED_MARKER_TTL);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isSinkCommittedMarkerPresent(long checkpointId) {
        try {
            RBucket<String> b = redissonClient.getBucket(sinkCommittedMarkerKey(checkpointId), StringCodec.INSTANCE);
            return b.isExists();
        } catch (Exception e) {
            return false;
        }
    }

    public String sinkCommittedMarkerKey(long checkpointId) {
        return storage.getKeyPrefix() + SINK_COMMITTED_MARKER_PREFIX + checkpointId;
    }

    public boolean restoreFromLatestCheckpoint(List<PipelineKey> pipelines) {
        return restoreFromLatestCheckpointOrNull(pipelines) != null;
    }

    public Checkpoint restoreFromLatestCheckpointOrNull(List<PipelineKey> pipelines) {
        Checkpoint latest = config.isDeferAckUntilCheckpoint()
                ? getLatestSinkCommittedCheckpoint()
                : getLatestCheckpointForRestore();
        if (config.isDeferAckUntilCheckpoint()) {
            // A two-phase-commit epoch is stored before it is committed, so the newest
            // checkpoint can be in doubt: its offsets and its transaction handles were
            // captured together, and adopting both lets recovery finalize the staged data
            // with recoverAndCommit instead of re-processing those records. The lookup
            // already refuses a superseded or discarded epoch, so whatever it returns is by
            // construction newer than any sink-committed checkpoint.
            Checkpoint inDoubt = getLatestInDoubtTwoPhaseCheckpoint();
            if (inDoubt != null) {
                latest = inDoubt;
            }
        }
        if (latest == null) {
            return null;
        }
        if (isForeignJobCheckpoint(latest)) {
            // A checkpoint whose meta declares a different jobName does not belong to this
            // job: adopting it would be wrong and a fresh start for this job is the correct
            // outcome, so this is "nothing to restore", not a failed restore.
            return null;
        }
        if (!restoreFromCheckpoint(latest, pipelines)) {
            // RT-M4: restore was explicitly requested and a checkpoint exists — a failed
            // restore must not degrade into a silent stateless start (consuming from
            // scratch with the keyed state lost). The caller surfaces this as a startup
            // failure; no-checkpoint-yet still returns null above and starts fresh.
            throw new IllegalStateException("Requested restore of checkpoint " + latest.getCheckpointId()
                    + " failed (jobName=" + config.getJobName()
                    + "); refusing to start without the restored state");
        }
        return latest;
    }

    /**
     * True when the checkpoint's meta carries a jobName that differs from this job's.
     * Unreadable meta is not an ownership mismatch — it is an operational failure and
     * lets the caller's restore (and its fail-stop) decide.
     */
    private boolean isForeignJobCheckpoint(Checkpoint checkpoint) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = checkpoint.getStateSnapshot().getState(SNAPSHOT_KEY_META);
            if (meta == null) {
                return false;
            }
            Object jobName = meta.get("jobName");
            return jobName != null && !String.valueOf(jobName).equals(config.getJobName());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean restoreFromCheckpoint(Checkpoint checkpoint, List<PipelineKey> pipelines) {
        if (checkpoint == null) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = checkpoint.getStateSnapshot().getState(SNAPSHOT_KEY_META);
            if (meta != null) {
                Object jobName = meta.get("jobName");
                if (jobName != null && !String.valueOf(jobName).equals(config.getJobName())) {
                    log.warn("Skip restore: checkpoint jobName mismatch (expected {}, got {})", config.getJobName(), jobName);
                    return false;
                }
            }

            offsetSnapshots.restoreOffsets(checkpoint, pipelines);
            stateSnapshots.restoreState(checkpoint);
            return true;
        } catch (Exception e) {
            log.warn("Failed to restore from checkpoint {}", checkpoint.getCheckpointId(), e);
            return false;
        }
    }

    public Checkpoint getLatestSinkCommittedCheckpoint() {
        try {
            for (Checkpoint c : listCheckpointsForRestore()) {
                if (c == null) continue;
                if (isSinkCommitted(c)) {
                    return c;
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to scan for sink-committed checkpoints", e);
            return null;
        }
    }

    /**
     * The newest checkpoint that stores two-phase-commit transaction handles while never
     * having been marked sink-committed: its transactions are pre-committed and durable but
     * not visible, because the process died (or the commit threw) between the checkpoint
     * store and the commit phase.
     *
     * <p>Only the newest checkpoint qualifies. One that was later discarded through
     * {@link #markTxnEpochAborted} is skipped — its data no longer exists, so adopting its
     * offsets would skip records that were never written anywhere. One that an even newer
     * sink-committed checkpoint superseded is skipped as well: both reference the same open
     * transaction, which that newer checkpoint already finalized.</p>
     */
    public Checkpoint getLatestInDoubtTwoPhaseCheckpoint() {
        try {
            // newest first: the first checkpoint decides. A sink-committed one means the
            // epoch of any older in-doubt checkpoint was already finalized by it (both
            // checkpoints reference the same open transaction), so replaying that older
            // handle would double-commit — such an epoch is superseded, not in doubt.
            for (Checkpoint c : listCheckpointsForRestore()) {
                if (c == null) continue;
                if (isSinkCommitted(c)) {
                    return null;
                }
                if (getTxnHandles(c).isEmpty()) {
                    continue;
                }
                if (isTxnEpochAborted(c.getCheckpointId())) {
                    log.info("Skipping in-doubt checkpoint {}: its transaction epoch was discarded",
                            c.getCheckpointId());
                    return null;
                }
                return c;
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to scan for in-doubt two-phase-commit checkpoints", e);
            return null;
        }
    }

    /**
     * Records that the transaction epoch stored in this checkpoint was discarded (a later
     * checkpoint failed before it was stored), so the checkpoint must never be adopted as
     * a restore point.
     */
    public boolean markTxnEpochAborted(long checkpointId) {
        try {
            RBucket<String> b = redissonClient.getBucket(txnAbortedMarkerKey(checkpointId), StringCodec.INSTANCE);
            b.set("1");
            return true;
        } catch (Exception e) {
            log.debug("Failed to mark checkpoint {} txn epoch aborted", checkpointId, e);
            return false;
        }
    }

    public boolean isTxnEpochAborted(long checkpointId) {
        try {
            RBucket<String> b = redissonClient.getBucket(txnAbortedMarkerKey(checkpointId), StringCodec.INSTANCE);
            return b.isExists();
        } catch (Exception e) {
            return false;
        }
    }

    public String txnAbortedMarkerKey(long checkpointId) {
        return storage.getKeyPrefix() + TXN_ABORTED_MARKER_PREFIX + checkpointId;
    }

    /** A checkpoint is sink-committed once the marker exists or the snapshot meta says so. */
    @SuppressWarnings("unchecked")
    private boolean isSinkCommitted(Checkpoint c) throws Exception {
        if (isSinkCommittedMarkerPresent(c.getCheckpointId())) {
            return true;
        }
        Map<String, Object> meta = c.getStateSnapshot().getState(SNAPSHOT_KEY_META);
        return meta != null && Boolean.TRUE.equals(meta.get("sinkCommitted"));
    }

    /** Fencing token carried by a checkpoint's meta; 0 when absent (pre-election checkpoints). */
    @SuppressWarnings("unchecked")
    private static long fencingTokenOf(Checkpoint c) {
        try {
            Map<String, Object> meta = c.getStateSnapshot().getState(SNAPSHOT_KEY_META);
            if (meta != null && meta.get("fencingToken") instanceof Number n) {
                return Math.max(0, n.longValue());
            }
        } catch (Exception ignore) {
            // best-effort: treat unreadable meta as token 0
        }
        return 0L;
    }

    /**
     * Checkpoints eligible for restore, newest first. When leader election is active and
     * any retained checkpoint carries a fencing token, only checkpoints with the maximum
     * token are eligible: a stale leader that kept writing after losing the lease holds
     * an older token, and adopting its checkpoint would roll the job back. Without
     * tokens (election disabled, or no leader-written checkpoint yet) the full history
     * is eligible.
     */
    private List<Checkpoint> listCheckpointsForRestore() {
        List<Checkpoint> all;
        try {
            all = storage.listCheckpoints(Integer.MAX_VALUE);
        } catch (Exception e) {
            log.debug("Failed to list checkpoints for restore", e);
            return List.of();
        }
        if (leaderElector == null) {
            return all;
        }
        long maxToken = 0;
        boolean anyToken = false;
        for (Checkpoint c : all) {
            long t = fencingTokenOf(c);
            if (t > 0) {
                anyToken = true;
                if (t > maxToken) {
                    maxToken = t;
                }
            }
        }
        if (!anyToken) {
            return all;
        }
        List<Checkpoint> filtered = new ArrayList<>(all.size());
        for (Checkpoint c : all) {
            if (fencingTokenOf(c) == maxToken) {
                filtered.add(c);
            }
        }
        return filtered;
    }

    /**
     * The newest checkpoint eligible for restore (fencing-token filtered). Returns null
     * when no checkpoint exists. Unlike {@link #getLatestCheckpoint()} this never returns
     * a stale leader's checkpoint.
     */
    public Checkpoint getLatestCheckpointForRestore() {
        List<Checkpoint> eligible = listCheckpointsForRestore();
        return eligible.isEmpty() ? null : eligible.get(0);
    }


    /**
     * RT-H2: evicts checkpoints beyond {@code checkpointsToKeep} (incomplete ones first, then
     * the oldest completed ones). Public so a stop-the-world caller can run it after resuming
     * the consumers instead of inside the pause; it lists and deserializes every retained
     * checkpoint, so the call is not free.
     */
    public void cleanupOld() {
        int keep = config.getCheckpointsToKeep();
        if (keep <= 0) {
            return;
        }
        try {
            List<Checkpoint> all = storage.listCheckpoints(Integer.MAX_VALUE);
            if (all.size() <= keep) {
                return;
            }
            for (int i = keep; i < all.size(); i++) {
                long checkpointId = all.get(i).getCheckpointId();
                try {
                    storage.deleteCheckpoint(checkpointId);
                } catch (Exception ex) {
                    log.warn("Checkpoint state operation failed", ex);
                }
                try {
                    RBucket<String> b = redissonClient.getBucket(sinkCommittedMarkerKey(checkpointId), StringCodec.INSTANCE);
                    b.delete();
                } catch (Exception ex) {
                    log.warn("Checkpoint state operation failed", ex);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to cleanup old checkpoints", e);
        }
    }

    public record PipelineKey(String topic, String consumerGroup) {
        public PipelineKey {
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(consumerGroup, "consumerGroup");
        }

        public String key() {
            return topic + "|" + consumerGroup;
        }
    }
}
