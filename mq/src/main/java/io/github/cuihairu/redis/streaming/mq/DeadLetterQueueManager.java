package io.github.cuihairu.redis.streaming.mq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadHeaders;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.impl.StreamEntryCodec;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Backwards-compatible Dead Letter Queue manager for the MQ module.
 *
 * This utility operates directly on DLQ stream keys for a topic and provides
 * simple admin operations: size/list/delete/clear/replay.
 *
 * Prefer using the reliability DLQ service for richer features; this class is
 * kept for API compatibility and light admin tooling.
 */
@SuppressWarnings("deprecation")
public class DeadLetterQueueManager {
    private final RedissonClient redissonClient;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DeadLetterQueueManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** Return DLQ size for a topic (best-effort). */
    public long getDeadLetterQueueSize(String topic) {
        String key = StreamKeys.dlq(topic);
        try {
            long sz = redissonClient.getStream(key).size();
            if (sz > 0) return sz;
        } catch (Exception ignore) {
            // fall through to codec fallback
        }
        try {
            long sz2 = redissonClient.getStream(key, org.redisson.client.codec.StringCodec.INSTANCE).size();
            if (sz2 > 0) return sz2;
        } catch (Exception ignore) {}
        try {
            @SuppressWarnings({"deprecation", "unchecked"})
            Map<StreamMessageId, Map<String, Object>> any = (Map) redissonClient.getStream(key)
                    .range(1, StreamMessageId.MIN, StreamMessageId.MAX);
            if (any != null && !any.isEmpty()) return any.size();
        } catch (Exception ignore) {}
        try {
            @SuppressWarnings({"deprecation", "unchecked"})
            Map<StreamMessageId, Map<String, Object>> any = (Map) redissonClient.getStream(key, org.redisson.client.codec.StringCodec.INSTANCE)
                    .range(1, StreamMessageId.MIN, StreamMessageId.MAX);
            if (any != null && !any.isEmpty()) return any.size();
        } catch (Exception ignore) {}
        return 0;
    }

    /** List recent DLQ entries for a topic (ascending by id). */
    public Map<StreamMessageId, Map<String, Object>> getDeadLetterMessages(String topic, int limit) {
        String key = StreamKeys.dlq(topic);
        try {
            // Try default codec first, then fallback to StringCodec to read entries regardless of writer codec
            RStream<String, Object> dlqDefault = redissonClient.getStream(key);
            Map<StreamMessageId, Map<String, Object>> res = null;
            try { res = dlqDefault.range(Math.max(1, limit), StreamMessageId.MIN, StreamMessageId.MAX); } catch (Exception ignore) {}
            if (res == null || res.isEmpty()) {
                try {
                    RStream<String, Object> dlqStr = redissonClient.getStream(key, org.redisson.client.codec.StringCodec.INSTANCE);
                    res = dlqStr.range(Math.max(1, limit), StreamMessageId.MIN, StreamMessageId.MAX);
                } catch (Exception ignore) {}
            }
            return (res != null) ? res : Collections.emptyMap();
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** Delete a specific DLQ entry. */
    public boolean deleteMessage(String topic, StreamMessageId id) {
        String key = StreamKeys.dlq(topic);
        try {
            return redissonClient.getStream(key).remove(id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Clear the DLQ stream for a topic; returns approximate deleted count. */
    public long clearDeadLetterQueue(String topic) {
        String key = StreamKeys.dlq(topic);
        try {
            long size = redissonClient.getStream(key).size();
            long deleted = redissonClient.getKeys().delete(key);
            return deleted > 0 ? size : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Replay a DLQ entry back to the original topic/partition, returns true on success. */
    public boolean replayMessage(String topic, StreamMessageId id) {
        String dlqKey = StreamKeys.dlq(topic);
        try {
            // Try default codec first, then StringCodec fallback (entries may be written with either)
            Map<StreamMessageId, Map<String, Object>> one = null;
            Map<String, Object> data = null;
            try {
                RStream<String, Object> dlqDef = redissonClient.getStream(dlqKey);
                one = dlqDef.range(1, id, id);
                if (one != null && !one.isEmpty()) data = one.get(id);
            } catch (Exception ignore) {}
            if (data == null) {
                try {
                    RStream<String, Object> dlqStr = redissonClient.getStream(dlqKey, org.redisson.client.codec.StringCodec.INSTANCE);
                    one = dlqStr.range(1, id, id);
                    if (one != null && !one.isEmpty()) data = one.get(id);
                } catch (Exception ignore) {}
            }
            if (data == null) return false;

            // Determine partition
            int pid = 0;
            Object pidVal = data.get("partitionId");
            if (pidVal instanceof Number) pid = ((Number) pidVal).intValue();
            else if (pidVal != null) { try { pid = Integer.parseInt(String.valueOf(pidVal)); } catch (Exception ignore) {} }

            // Rebuild partition entry; if DLQ stored payload as hash, generate a fresh payload key to refresh TTL
            Map<String, String> headers = new HashMap<>();
            Object hdr = data.get("headers");
            if (hdr instanceof Map) {
                try { ((Map<?,?>) hdr).forEach((k,v) -> { if (k!=null && v!=null) headers.put(String.valueOf(k), String.valueOf(v)); }); } catch (Exception ignore) {}
            } else if (hdr instanceof String) {
                try { headers.putAll(MAPPER.readValue((String) hdr, new TypeReference<Map<String,String>>(){})); } catch (Exception ignore) {}
            }

            Object payload = data.get("payload");
            Map<String, Object> replay = new HashMap<>();
            PayloadLifecycleManager plm = new PayloadLifecycleManager(redissonClient);
            String storageType = headers.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE);
            if (PayloadHeaders.STORAGE_TYPE_HASH.equals(storageType)) {
                String oldRef = headers.get(PayloadHeaders.PAYLOAD_HASH_REF);
                if (oldRef != null && !oldRef.isEmpty()) {
                    // Load and re-store to a fresh key bound to the original topic/partition; refresh TTL
                    Object obj = plm.loadPayload(oldRef);
                    String newRef = plm.storeLargePayload(topic, pid, obj);
                    headers.put(PayloadHeaders.PAYLOAD_HASH_REF, newRef);
                    replay.put("payload", null);
                } else {
                    // Fallback: keep inline payload if available
                    replay.put("payload", payload);
                    headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
                }
            } else {
                // Inline storage path
                replay.put("payload", payload);
                headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
            }

            // Standard fields
            replay.put("timestamp", Instant.now().toString());
            replay.put("retryCount", 0);
            Object maxr = data.get("maxRetries");
            int maxRetries = 3;
            try { if (maxr != null) maxRetries = (maxr instanceof Number) ? ((Number) maxr).intValue() : Integer.parseInt(String.valueOf(maxr)); } catch (Exception ignore) {}
            replay.put("maxRetries", maxRetries);
            replay.put("topic", topic);
            replay.put("partitionId", pid);
            Object key = data.get("key"); if (key != null) replay.put("key", key);
            if (!headers.isEmpty()) replay.put("headers", headers);

            String streamKey = StreamKeys.partitionStream(topic, pid);
            RStream<String, Object> orig = redissonClient.getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE);
            return orig.add(StreamAddArgs.entries(replay)) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
