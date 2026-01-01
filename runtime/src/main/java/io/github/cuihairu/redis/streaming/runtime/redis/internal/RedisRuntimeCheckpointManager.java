package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.checkpoint.DefaultCheckpoint;
import io.github.cuihairu.redis.streaming.checkpoint.redis.RedisCheckpointStorage;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import io.github.cuihairu.redis.streaming.runtime.redis.RedisRuntimeConfig;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort checkpoint manager for Redis runtime.
 *
 * <p>Stores checkpoints in Redis and supports restoring consumer group offsets and keyed-state hashes.</p>
 */
public final class RedisRuntimeCheckpointManager {
    private static final Logger log = LoggerFactory.getLogger(RedisRuntimeCheckpointManager.class);

    private static final String SNAPSHOT_KEY_OFFSETS = "runtime:offsets";
    private static final String SNAPSHOT_KEY_STATE = "runtime:state";
    private static final String SNAPSHOT_KEY_STATE_SCHEMA = "runtime:stateSchema";
    private static final String SNAPSHOT_KEY_META = "runtime:meta";
    private static final String SINK_COMMITTED_MARKER_PREFIX = "runtime:sinkCommitted:";

    private final RedissonClient redissonClient;
    private final RedisRuntimeConfig config;
    private final RedisCheckpointStorage storage;
    private final TopicPartitionRegistry partitionRegistry;
    private final AtomicLong nextCheckpointId;

    public RedisRuntimeCheckpointManager(RedissonClient redissonClient, RedisRuntimeConfig config) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.config = Objects.requireNonNull(config, "config");
        String prefix = config.getCheckpointKeyPrefix() + config.getJobName() + ":";
        this.storage = new RedisCheckpointStorage(redissonClient, prefix);
        this.partitionRegistry = new TopicPartitionRegistry(redissonClient);
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

    public Checkpoint triggerCheckpoint(long checkpointId,
                                        List<PipelineKey> pipelines,
                                        Map<String, Map<Integer, String>> offsetsOverride) {
        DefaultCheckpoint cp = new DefaultCheckpoint(checkpointId, System.currentTimeMillis());
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("jobName", config.getJobName());
            meta.put("stateKeyPrefix", config.getStateKeyPrefix());
            meta.put("sinkCommitted", Boolean.FALSE);
            cp.getStateSnapshot().putState(SNAPSHOT_KEY_META, meta);

            cp.getStateSnapshot().putState(SNAPSHOT_KEY_OFFSETS, snapshotOffsets(pipelines, offsetsOverride));
            cp.getStateSnapshot().putState(SNAPSHOT_KEY_STATE, snapshotState());
            cp.getStateSnapshot().putState(SNAPSHOT_KEY_STATE_SCHEMA, snapshotStateSchema());

            cp.markCompleted();
            storage.storeCheckpoint(cp);

            cleanupOld();
            return cp;
        } catch (Exception e) {
            log.warn("Failed to store checkpoint {}", checkpointId, e);
            return null;
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
            b.set("1");
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
                : getLatestCheckpoint();
        if (latest == null) {
            return null;
        }
        return restoreFromCheckpoint(latest, pipelines) ? latest : null;
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

            restoreOffsets(checkpoint, pipelines);
            restoreState(checkpoint);
            return true;
        } catch (Exception e) {
            log.warn("Failed to restore from checkpoint {}", checkpoint.getCheckpointId(), e);
            return false;
        }
    }

    public Checkpoint getLatestSinkCommittedCheckpoint() {
        try {
            List<Checkpoint> all = storage.listCheckpoints(Integer.MAX_VALUE);
            for (Checkpoint c : all) {
                if (c == null) continue;
                try {
                    if (isSinkCommittedMarkerPresent(c.getCheckpointId())) {
                        return c;
                    }
                } catch (Exception ignore) {
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = c.getStateSnapshot().getState(SNAPSHOT_KEY_META);
                if (meta == null) continue;
                Object v = meta.get("sinkCommitted");
                if (Boolean.TRUE.equals(v)) {
                    return c;
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Failed to scan for sink-committed checkpoints", e);
            return null;
        }
    }

    private Map<String, Map<Integer, String>> snapshotOffsets(List<PipelineKey> pipelines,
                                                             Map<String, Map<Integer, String>> offsetsOverride) {
        Map<String, Map<Integer, String>> out = new HashMap<>();
        if (pipelines == null) {
            return out;
        }
        for (PipelineKey p : pipelines) {
            int pc = Math.max(1, partitionRegistry.getPartitionCount(p.topic()));
            Map<Integer, String> perPartition = new HashMap<>();
            Map<Integer, String> override = offsetsOverride == null ? null : offsetsOverride.get(p.key());
            for (int pid = 0; pid < pc; pid++) {
                if (override != null) {
                    String ov = override.get(pid);
                    if (ov != null && !ov.isBlank()) {
                        perPartition.put(pid, ov);
                        continue;
                    }
                }
                String committed = null;
                try {
                    @SuppressWarnings("rawtypes")
                    RMap frontier = redissonClient.getMap(StreamKeys.commitFrontier(p.topic(), pid));
                    Object v = frontier.get(p.consumerGroup());
                    committed = v == null ? null : String.valueOf(v);
                } catch (Exception ignore) {
                }
                perPartition.put(pid, committed);
            }
            out.put(p.key(), perPartition);
        }
        return out;
    }

    private Map<String, Map<String, String>> snapshotState() {
        Map<String, Map<String, String>> out = new HashMap<>();
        String indexKey = config.getStateKeyPrefix() + ":" + config.getJobName() + ":stateKeys";
        RSet<String> index = redissonClient.getSet(indexKey, StringCodec.INSTANCE);
        List<String> keys = new ArrayList<>();
        try {
            keys.addAll(index.readAll());
        } catch (Exception ignore) {
        }

        RKeys rkeys = redissonClient.getKeys();
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            try {
                if (rkeys.countExists(k) <= 0) {
                    try {
                        index.remove(k);
                    } catch (Exception ignore) {
                    }
                    continue;
                }
                RMap<String, String> map = redissonClient.<String, String>getMap(k, StringCodec.INSTANCE);
                Map<String, String> data = map.readAllMap();
                if (data != null && !data.isEmpty()) {
                    out.put(k, new HashMap<>(data));
                }
            } catch (Exception e) {
                log.debug("Failed to snapshot state key {}", k, e);
            }
        }
        return out;
    }

    private Map<String, String> snapshotStateSchema() {
        Map<String, String> out = new HashMap<>();
        String indexKey = config.getStateKeyPrefix() + ":" + config.getJobName() + ":stateKeys";
        String schemaKey = config.getStateKeyPrefix() + ":" + config.getJobName() + ":stateSchema";
        RSet<String> index = redissonClient.getSet(indexKey, StringCodec.INSTANCE);
        List<String> keys = new ArrayList<>();
        try {
            keys.addAll(index.readAll());
        } catch (Exception ignore) {
        }

        RMap<String, String> schema = redissonClient.getMap(schemaKey, StringCodec.INSTANCE);
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            try {
                String v = schema.get(k);
                if (v != null && !v.isBlank()) {
                    out.put(k, v);
                }
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    private void restoreOffsets(Checkpoint checkpoint, List<PipelineKey> pipelines) {
        @SuppressWarnings("unchecked")
        Map<String, Map<Integer, String>> offsets = checkpoint.getStateSnapshot().getState(SNAPSHOT_KEY_OFFSETS);
        if (offsets == null || offsets.isEmpty()) {
            return;
        }
        if (pipelines == null) {
            return;
        }

        RScript script = redissonClient.getScript(StringCodec.INSTANCE);
        final String lua =
                "redis.pcall('XGROUP','DESTROY', KEYS[1], ARGV[1]) \n" +
                "local r = redis.pcall('XGROUP','CREATE', KEYS[1], ARGV[1], ARGV[2], 'MKSTREAM') \n" +
                "if type(r)=='table' and r.err then if string.find(r.err,'BUSYGROUP') then return 'EXISTS' else return r.err end end \n" +
                "return r";

        for (PipelineKey p : pipelines) {
            Map<Integer, String> perPartition = offsets.get(p.key());
            if (perPartition == null) {
                continue;
            }
            int pc = Math.max(1, partitionRegistry.getPartitionCount(p.topic()));
            for (int pid = 0; pid < pc; pid++) {
                String id = perPartition.get(pid);
                String startId = (id == null || id.isBlank()) ? "0-0" : id;
                String streamKey = StreamKeys.partitionStream(p.topic(), pid);
                try {
                    script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.STATUS,
                            java.util.Collections.singletonList(streamKey), p.consumerGroup(), startId);
                } catch (Exception e) {
                    log.debug("Failed to restore group offset: topic={}, group={}, partition={}, id={}",
                            p.topic(), p.consumerGroup(), pid, startId, e);
                }
            }
        }
    }

    private void restoreState(Checkpoint checkpoint) {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> state = checkpoint.getStateSnapshot().getState(SNAPSHOT_KEY_STATE);
        if (state == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> schemaSnap = checkpoint.getStateSnapshot().getState(SNAPSHOT_KEY_STATE_SCHEMA);

        String indexKey = config.getStateKeyPrefix() + ":" + config.getJobName() + ":stateKeys";
        String schemaKey = config.getStateKeyPrefix() + ":" + config.getJobName() + ":stateSchema";
        RSet<String> index = redissonClient.getSet(indexKey, StringCodec.INSTANCE);
        RMap<String, String> schema = redissonClient.getMap(schemaKey, StringCodec.INSTANCE);
        List<String> existing = new ArrayList<>();
        try {
            existing.addAll(index.readAll());
        } catch (Exception ignore) {
        }

        RKeys rkeys = redissonClient.getKeys();
        for (String k : existing) {
            if (k == null || k.isBlank()) continue;
            try {
                rkeys.delete(k);
            } catch (Exception ignore) {
            }
        }
        try {
            index.clear();
        } catch (Exception ignore) {
        }
        try {
            schema.clear();
        } catch (Exception ignore) {
        }

        Duration ttl = config.getStateTtl();
        for (Map.Entry<String, Map<String, String>> e : state.entrySet()) {
            String redisKey = e.getKey();
            Map<String, String> data = e.getValue();
            if (redisKey == null || redisKey.isBlank() || data == null || data.isEmpty()) {
                continue;
            }
            try {
                RMap<String, String> map = redissonClient.<String, String>getMap(redisKey, StringCodec.INSTANCE);
                map.putAll(data);
                if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                    try {
                        map.expire(ttl);
                    } catch (Exception ignore) {
                    }
                }
                try {
                    index.add(redisKey);
                } catch (Exception ignore) {
                }
                if (schemaSnap != null) {
                    String sv = schemaSnap.get(redisKey);
                    if (sv != null && !sv.isBlank()) {
                        try {
                            schema.put(redisKey, sv);
                        } catch (Exception ignore) {
                        }
                    }
                }
            } catch (Exception ex) {
                log.debug("Failed to restore state key {}", redisKey, ex);
            }
        }
    }

    private void cleanupOld() {
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
                } catch (Exception ignore) {
                }
                try {
                    RBucket<String> b = redissonClient.getBucket(sinkCommittedMarkerKey(checkpointId), StringCodec.INSTANCE);
                    b.delete();
                } catch (Exception ignore) {
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
