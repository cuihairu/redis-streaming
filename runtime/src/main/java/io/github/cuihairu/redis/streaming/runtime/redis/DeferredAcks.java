package io.github.cuihairu.redis.streaming.runtime.redis;

import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.runtime.redis.internal.RedisRuntimeCheckpointManager;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks messages whose Redis ack is deferred until the covering checkpoint commits
 * (two-phase commit over consumer offsets).
 *
 * <p>Pipelines are keyed by the structured {@link RedisRuntimeCheckpointManager.PipelineKey}
 * instead of the old {@code topic + "|" + group} string (RT-L2): a topic containing
 * {@code |} used to split back wrong at ack time, acking the wrong stream. The offsets
 * snapshot still carries the {@link RedisRuntimeCheckpointManager.PipelineKey#key()}
 * string form so the checkpoint-side {@code offsetsOverride.get(p.key())} lookups stay
 * byte-compatible.</p>
 */
final class DeferredAcks {
    private static final Logger log = LoggerFactory.getLogger(DeferredAcks.class);

    /**
     * MQ-11: atomic frontier compare-and-set — HSET only when the acked stream id is
     * newer than the stored one. Values are plain text "ms-seq"; quotes are stripped
     * before matching so values written by a quoting codec stay comparable. A new id
     * that is not a stream id is never written; a stored value that is not a stream id
     * is overwritten (self-healing, e.g. hashes left behind by a previous binary codec).
     * Returns 1 when the frontier advanced.
     */
    private static final String COMMIT_FRONTIER_CAS_LUA = String.join("\n",
            "local prev = redis.call('HGET', KEYS[1], ARGV[1])",
            "local nv = string.gsub(ARGV[2], '\"', '')",
            "local nm, ns = string.match(nv, '^(%d+)-(%d+)$')",
            "if nm == nil then return 0 end",
            "if prev == false then",
            "  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])",
            "  return 1",
            "end",
            "local pv = string.gsub(prev, '\"', '')",
            "local pm, ps = string.match(pv, '^(%d+)-(%d+)$')",
            "if pm == nil then",
            "  -- fallback to lexicographic compare for unparsable values",
            "  if nv > prev then",
            "    redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])",
            "    return 1",
            "  end",
            "  return 0",
            "end",
            "if tonumber(nm) > tonumber(pm) or (tonumber(nm) == tonumber(pm) and tonumber(ns) > tonumber(ps)) then",
            "  redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])",
            "  return 1",
            "end",
            "return 0");

    private final java.util.concurrent.ConcurrentHashMap<RedisRuntimeCheckpointManager.PipelineKey, java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>>> byPipeline =
            new java.util.concurrent.ConcurrentHashMap<>();

    void record(String topic, String consumerGroup, int partitionId, String messageId) {
        byPipeline.computeIfAbsent(new RedisRuntimeCheckpointManager.PipelineKey(topic, consumerGroup),
                        k -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(partitionId, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(messageId);
    }

    Map<String, Map<Integer, String>> snapshotOffsets() {
        Map<String, Map<Integer, String>> out = new HashMap<>();
        for (Map.Entry<RedisRuntimeCheckpointManager.PipelineKey, java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>>> e : byPipeline.entrySet()) {
            Map<Integer, String> per = new HashMap<>();
            for (Map.Entry<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>> pe : e.getValue().entrySet()) {
                // RT-L2: the frontier must be the furthest id, not merely the one
                // appended last — the queue order is the poll order, which is not
                // guaranteed to be ascending under concurrent readers
                String maxId = null;
                for (String id : pe.getValue()) {
                    if (StreamIds.compareStreamId(id, maxId) > 0) {
                        maxId = id;
                    }
                }
                if (maxId != null && !maxId.isBlank()) {
                    per.put(pe.getKey(), maxId);
                }
            }
            if (!per.isEmpty()) {
                out.put(e.getKey().key(), per);
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

    /**
     * Removes and returns every id recorded so far, keyed pipeline -> partition ->
     * ids. The checkpoint calls this once its two-phase epoch is prepared: anything
     * recorded afterwards belongs to the NEXT epoch (its side effects are not committed
     * by this checkpoint), so it must stay tracked for the next round instead of being
     * acked by this one — acking it would orphan the record if the process died before
     * its own epoch committed.
     */
    Map<RedisRuntimeCheckpointManager.PipelineKey, Map<Integer, java.util.List<String>>> drainForAck() {
        Map<RedisRuntimeCheckpointManager.PipelineKey, Map<Integer, java.util.List<String>>> out = new HashMap<>();
        for (Map.Entry<RedisRuntimeCheckpointManager.PipelineKey, java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>>> e : byPipeline.entrySet()) {
            Map<Integer, java.util.List<String>> per = new HashMap<>();
            for (Map.Entry<Integer, java.util.concurrent.ConcurrentLinkedQueue<String>> pe : e.getValue().entrySet()) {
                java.util.List<String> ids = new java.util.ArrayList<>();
                String id;
                while ((id = pe.getValue().poll()) != null) {
                    ids.add(id);
                }
                if (!ids.isEmpty()) {
                    per.put(pe.getKey(), ids);
                }
            }
            if (!per.isEmpty()) {
                out.put(e.getKey(), per);
            }
        }
        return out;
    }

    /**
     * Acks exactly the frozen snapshot the owning checkpoint prepared via
     * {@link #drainForAck()} — and nothing else. There is deliberately no
     * "ack everything currently tracked" convenience: acking ids outside the frozen
     * set would advance the commit frontier over records whose epoch has not
     * committed yet (the ack must stay synchronized with the checkpoint's success,
     * not with the caller's whim).
     */
    void ackAll(RedissonClient redissonClient,
                Map<RedisRuntimeCheckpointManager.PipelineKey, Map<Integer, java.util.List<String>>> snapshot,
                StreamKeys keys) {
        if (redissonClient == null || snapshot == null) {
            return;
        }
        for (Map.Entry<RedisRuntimeCheckpointManager.PipelineKey, Map<Integer, java.util.List<String>>> e : snapshot.entrySet()) {
            String topic = e.getKey().topic();
            String group = e.getKey().consumerGroup();
            for (Map.Entry<Integer, java.util.List<String>> pe : e.getValue().entrySet()) {
                int pid = pe.getKey();
                java.util.List<StreamMessageId> ids = new java.util.ArrayList<>();
                String maxId = null;
                for (String id : pe.getValue()) {
                    ids.add(StreamIds.parseStreamId(id));
                    // RT-L2: take the furthest id for the frontier, not the last in
                    // the (poll-ordered) list
                    if (StreamIds.compareStreamId(id, maxId) > 0) {
                        maxId = id;
                    }
                }
                if (ids.isEmpty()) {
                    continue;
                }
                boolean acked = false;
                RStream<String, Object> stream = null;
                try {
                    stream = redissonClient.getStream(keys.partitionStreamKey(topic, pid), org.redisson.client.codec.StringCodec.INSTANCE);
                    stream.ack(group, ids.toArray(new StreamMessageId[0]));
                    acked = true;
                } catch (Exception ex) {
                    log.warn("Failed to ack deferred messages (topic={}, group={}, partition={}, count={})", topic, group, pid, ids.size(), ex);
                    // Audit fix: drainForAck already removed this batch from tracking, so
                    // dropping it here would let a later successful batch advance the
                    // frontier past ids that never left the PEL (9-2 fails, 9-4 succeeds,
                    // frontier=9-4 with 9-2 still pending). Re-record the batch so the
                    // next checkpoint round re-drains and retries it.
                    for (String id : pe.getValue()) {
                        record(topic, group, pid, id);
                    }
                }
                // MQ-11 invariant: the commit frontier may only advance over acked records —
                // and (audit fix) only past records that have actually left the PEL. The
                // frontier is the retention trim boundary: advancing it over a still-pending
                // id lets the housekeeper XTRIM that message (silent data loss), which the
                // old "advance over the max acked id" rule did whenever an earlier batch's
                // XACK failed. Clamp the CAS to the PEL minimum: advance only when nothing
                // pending sits at or below maxId; a failed pending query skips the advance —
                // the frontier staying behind is the safe direction.
                if (acked && maxId != null) {
                    String pendingMin = null;
                    boolean pelKnown = true;
                    try {
                        pendingMin = oldestPendingId(stream, group);
                    } catch (Exception ex) {
                        if (isNoGroupError(ex)) {
                            // a group without a PEL has nothing pending — the advance is safe
                            pendingMin = null;
                        } else {
                            pelKnown = false;
                            log.warn("Failed to read the pending minimum (topic={}, group={}, partition={}) — frontier stays behind", topic, group, pid, ex);
                        }
                    }
                    if (pelKnown) {
                        if (pendingMin != null && StreamIds.compareStreamId(maxId, pendingMin) >= 0) {
                            log.warn("Frontier held at the pending minimum (topic={}, group={}, partition={}, maxId={}, pendingMin={})",
                                    topic, group, pid, maxId, pendingMin);
                        } else {
                            try {
                                // MQ-11: the frontier is a max() reduced across concurrent ack
                                // workers, so the compare-and-set runs inside Redis as one
                                // atomic Lua script over plain text "ms-seq" values — the old
                                // Java read-then-put could read a stale prev and land a
                                // smaller id last, regressing the frontier
                                RScript script = redissonClient.getScript(org.redisson.client.codec.StringCodec.INSTANCE);
                                script.eval(RScript.Mode.READ_WRITE, COMMIT_FRONTIER_CAS_LUA,
                                        RScript.ReturnType.LONG,
                                        java.util.Collections.singletonList(keys.commitFrontierKey(topic, pid)),
                                        group, maxId);
                            } catch (Exception ex) {
                                // a failed CAS leaves the frontier behind — the safe direction
                                // (acked ids simply stay untrimmed until a later advance); make it
                                // visible instead of silently swallowed
                                log.warn("Failed to advance commit frontier (topic={}, group={}, partition={}, maxId={})", topic, group, pid, maxId, ex);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The minimum id still pending in the group's PEL, or null when the PEL is empty.
     * This is the retention boundary: Redis delivers in ascending id order, so every
     * entry below the pending minimum has been delivered and is not pending — the
     * frontier may safely advance up to, but never past, it. Throws when Redis cannot
     * be consulted; callers treat the query failure as "unknown, do not advance".
     */
    private static String oldestPendingId(RStream<String, Object> stream, String group) {
        // the XPENDING summary form carries the lowest pending id in one command
        org.redisson.api.stream.PendingResult pending = stream.getPendingInfo(group);
        if (pending == null || pending.getTotal() <= 0) {
            return null;
        }
        return formatPendingId(pending.getLowestId());
    }

    /** True when Redis rejected the command with NOGROUP — the group has no PEL. */
    private static boolean isNoGroupError(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            String m = t.getMessage();
            if (m != null && m.contains("NOGROUP")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String formatPendingId(StreamMessageId id) {
        if (id == null) {
            return null;
        }
        long ms = id.getId0();
        if (ms < 0) {
            return null;
        }
        long seq = Math.max(id.getId1(), 0);
        return ms + "-" + seq;
    }
}
