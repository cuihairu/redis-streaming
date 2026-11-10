package io.github.cuihairu.redis.streaming.runtime.redis.internal;

import io.github.cuihairu.redis.streaming.api.checkpoint.Checkpoint;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumer-offset half of {@link RedisRuntimeCheckpointManager}: snapshots commit-frontier
 * offsets per pipeline partition and rebuilds the consumer groups from them on restore.
 */
final class RedisRuntimeOffsetSnapshotter {

    private static final Logger log = LoggerFactory.getLogger(RedisRuntimeOffsetSnapshotter.class);

    private final RedisRuntimeCheckpointManager manager;

    RedisRuntimeOffsetSnapshotter(RedisRuntimeCheckpointManager manager) {
        this.manager = manager;
    }

    Map<String, Map<Integer, String>> snapshotOffsets(List<RedisRuntimeCheckpointManager.PipelineKey> pipelines,
                                                             Map<String, Map<Integer, String>> offsetsOverride) {
        Map<String, Map<Integer, String>> out = new HashMap<>();
        if (pipelines == null) {
            return out;
        }
        for (RedisRuntimeCheckpointManager.PipelineKey p : pipelines) {
            int pc = Math.max(1, manager.partitionRegistry.getPartitionCount(p.topic()));
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
                    // MQ-11: the frontier hash is plain text "ms-seq" written by the mq
                    // consumer's Lua script (StringCodec), regardless of the client codec
                    RMap<String, String> frontier = manager.redissonClient.getMap(
                            manager.keys.commitFrontierKey(p.topic(), pid), StringCodec.INSTANCE);
                    String v = frontier.get(p.consumerGroup());
                    committed = v;
                } catch (Exception ex) {
                    // RT-M5: a transient Redis error here records no offset, and restore then
                    // rewinds this partition to 0-0 (full reprocess) — that deserves a warning,
                    // not a debug line that hides the rewind.
                    log.warn("Failed to read commit frontier for topic={}, group={}, partition={}; "
                            + "restore will rewind this partition to 0-0",
                            p.topic(), p.consumerGroup(), pid, ex);
                }
                perPartition.put(pid, committed);
            }
            out.put(p.key(), perPartition);
        }
        return out;
    }

    void restoreOffsets(Checkpoint checkpoint, List<RedisRuntimeCheckpointManager.PipelineKey> pipelines) {
        @SuppressWarnings("unchecked")
        Map<String, Map<Integer, String>> offsets = checkpoint.getStateSnapshot().getState(RedisRuntimeCheckpointManager.SNAPSHOT_KEY_OFFSETS);
        if (offsets == null || offsets.isEmpty()) {
            return;
        }
        if (pipelines == null) {
            return;
        }

        RScript script = manager.redissonClient.getScript(StringCodec.INSTANCE);
        final String lua =
                "redis.pcall('XGROUP','DESTROY', KEYS[1], ARGV[1]) \n" +
                "local r = redis.pcall('XGROUP','CREATE', KEYS[1], ARGV[1], ARGV[2], 'MKSTREAM') \n" +
                "if type(r)=='table' and r.err then if string.find(r.err,'BUSYGROUP') then return 'EXISTS' else return r.err end end \n" +
                "return r";

        for (RedisRuntimeCheckpointManager.PipelineKey p : pipelines) {
            Map<Integer, String> perPartition = offsets.get(p.key());
            if (perPartition == null) {
                continue;
            }
            int pc = Math.max(1, manager.partitionRegistry.getPartitionCount(p.topic()));
            for (int pid = 0; pid < pc; pid++) {
                String id = offsetForPartition(perPartition, pid);
                String startId = (id == null || id.isBlank()) ? "0-0" : id;
                String streamKey = manager.keys.partitionStreamKey(p.topic(), pid);
                try {
                    script.eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.STRING,
                            java.util.Collections.singletonList(streamKey), p.consumerGroup(), startId);
                } catch (Exception e) {
                    log.warn("Failed to restore group offset: topic={}, group={}, partition={}, id={}",
                            p.topic(), p.consumerGroup(), pid, startId, e);
                }
            }
        }
    }

    /**
     * Looks up a partition's committed offset tolerating the JSON round-trip through
     * {@code RedisCheckpointStorage}: the snapshot is built with {@code Integer} manager.keys, but the
     * default Jackson codec stringifies them on write, so the deserialized map is keyed by
     * {@code String}. The plain {@code get(pid)} lookup used to miss every entry, silently
     * rewinding every restored consumer group to {@code 0-0} (RT-H1).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static String offsetForPartition(Map<Integer, String> perPartition, int pid) {
        String id = perPartition.get(pid);
        if (id == null) {
            Object v = ((Map) perPartition).get(String.valueOf(pid));
            if (v != null) {
                id = String.valueOf(v);
            }
        }
        return id;
    }
}
