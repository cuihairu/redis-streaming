package io.github.cuihairu.redis.streaming.mq.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.*;

/**
 * Manages lifecycle of large payload storage in Redis.
 *
 * Key principles:
 * - Keyspace prefix is derived from MQ configuration (control prefix), not hard-coded.
 * - Avoid KEYS/SCAN where possible: maintain per-topic index set and a global time index (ZSET).
 * - Deletion is primarily driven by ACK path; cleanup methods are best-effort safeguards.
 */
@Slf4j
public class PayloadLifecycleManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedissonClient redissonClient;
    private final String controlPrefix;
    private final String payloadPrefix; // {controlPrefix}:payload
    private final long ttlMs; // 0 means no TTL (prefer ACK deletion)

    /** Backward compatible constructor using configured StreamKeys prefix and no TTL. */
    public PayloadLifecycleManager(RedissonClient redissonClient) {
        this(redissonClient, null);
    }

    /** Preferred constructor: derive prefixes and TTL from options. */
    public PayloadLifecycleManager(RedissonClient redissonClient, MqOptions options) {
        this.redissonClient = redissonClient;
        // Prefer options.keyPrefix; fallback to currently configured StreamKeys.controlPrefix()
        String cp = options != null && options.getKeyPrefix() != null && !options.getKeyPrefix().isBlank()
                ? options.getKeyPrefix() : StreamKeys.controlPrefix();
        this.controlPrefix = cp;
        this.payloadPrefix = cp + ":payload";
        // TTL strategy: default disable to avoid payload disappearing before consumption
        long candidateTtl = 0L;
        if (options != null && options.getRetentionMs() > 0) {
            // If a time retention exists, TTL can be a relaxed upper bound; still allow disable by keeping 0 if desired.
            candidateTtl = options.getRetentionMs();
        }
        this.ttlMs = candidateTtl;
    }

    /** Generate a payload key: {controlPrefix}:payload:{topic}:p:{partitionId}:{uuid} */
    public String generatePayloadKey(String topic, int partitionId) {
        return payloadPrefix + ":" + topic + ":p:" + partitionId + ":" + UUID.randomUUID();
    }

    /** Store payload as JSON at generated key and index it. Returns the key reference. */
    public String storeLargePayload(String topic, int partitionId, Object payload) {
        String key = generatePayloadKey(topic, partitionId);
        storePayload(key, topic, payload);
        return key;
    }

    /** Load payload JSON by key and parse as generic Object. */
    public Object loadPayload(String key) {
        try {
            String jsonPayload = (String) redissonClient.getBucket(key, StringCodec.INSTANCE).get();
            if (jsonPayload == null) {
                throw new RuntimeException("Payload not found: " + key);
            }
            return MAPPER.readValue(jsonPayload, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load payload: " + key, e);
        }
    }

    /** Delete payload by key and remove from indices. */
    public boolean deletePayloadKey(String key) {
        if (key == null || key.isEmpty()) return false;
        try {
            boolean deleted = redissonClient.getBucket(key, StringCodec.INSTANCE).delete();
            // Best-effort index cleanup
            try { removeFromIndices(key); } catch (Exception ignore) {}
            if (deleted) {
                log.debug("Deleted payload: {}", key);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("Failed to delete payload: {}", key, e);
            return false;
        }
    }

    /** Extract payload key reference from stream entry map. Supports Map or JSON string headers. */
    public String extractPayloadHashRef(Map<String, Object> streamData) {
        if (streamData == null) return null;
        Object headersVal = streamData.get("headers");
        if (headersVal instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) headersVal;
            return headers.get(PayloadHeaders.PAYLOAD_HASH_REF);
        }
        if (headersVal instanceof String) {
            try {
                Map<String, String> headers = MAPPER.readValue((String) headersVal, new TypeReference<Map<String, String>>(){});
                return headers.get(PayloadHeaders.PAYLOAD_HASH_REF);
            } catch (Exception ignore) {}
        }
        return null;
    }

    /** Delete payload key referenced by provided streamData map. */
    public boolean deletePayloadHashFromStreamData(Map<String, Object> streamData) {
        String ref = extractPayloadHashRef(streamData);
        return deletePayloadKey(ref);
    }

    /**
     * Since message headers are sanitized on parse, this is mostly useful only if called before parse.
     * Kept for compatibility but might often return false.
     */
    public boolean deletePayloadHashFromMessage(Message message) {
        if (message == null || message.getHeaders() == null) return false;
        String ref = message.getHeaders().get(PayloadHeaders.PAYLOAD_HASH_REF);
        return deletePayloadKey(ref);
    }

    /** Cleanup all payload keys for a topic using the per-topic index set. */
    public long cleanupTopicPayloadHashes(String topic) {
        String idxKey = topicIndexKey(topic);
        try {
            RSet<String> idx = redissonClient.getSet(idxKey, StringCodec.INSTANCE);
            long cnt = 0;
            for (String key : new ArrayList<>(idx)) { // snapshot to avoid concurrent modification
                if (deletePayloadKey(key)) cnt++;
            }
            if (cnt > 0) log.info("Cleaned up {} payloads for topic {}", cnt, topic);
            return cnt;
        } catch (Exception e) {
            log.error("Failed to cleanup payloads for topic {}", topic, e);
            return 0;
        }
    }

    /** Cleanup orphan payloads by time using the global ZSET index. */
    public long cleanupOrphanedPayloadHashes(long olderThanMs) {
        try {
            long cutoff = System.currentTimeMillis() - Math.max(0, olderThanMs);
            RScoredSortedSet<String> z = redissonClient.getScoredSortedSet(globalTimeIndexKey(), StringCodec.INSTANCE);
            Collection<String> keys = z.valueRange(Double.NEGATIVE_INFINITY, true, cutoff, true);
            long deleted = 0;
            for (String key : keys) {
                if (deletePayloadKey(key)) deleted++;
            }
            if (deleted > 0) log.info("Cleaned up {} orphaned payloads (<= {})", deleted, cutoff);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to cleanup orphaned payloads", e);
            return 0;
        }
    }

    // ---- internal helpers ----

    private void storePayload(String key, String topic, Object payload) {
        try {
            String json = MAPPER.writeValueAsString(payload);
            RBucket<String> b = redissonClient.getBucket(key, StringCodec.INSTANCE);
            if (ttlMs > 0) {
                b.set(json, Duration.ofMillis(ttlMs));
            } else {
                b.set(json);
            }
            // indices
            try {
                redissonClient.getSet(topicIndexKey(topic), StringCodec.INSTANCE).add(key);
            } catch (Exception ignore) {}
            try {
                redissonClient.getScoredSortedSet(globalTimeIndexKey(), StringCodec.INSTANCE)
                        .add((double) System.currentTimeMillis(), key);
            } catch (Exception ignore) {}
        } catch (Exception e) {
            throw new RuntimeException("Failed to store payload: " + key, e);
        }
    }

    private void removeFromIndices(String key) {
        String topic = parseTopicFromPayloadKey(key);
        if (topic != null) {
            try { redissonClient.getSet(topicIndexKey(topic), StringCodec.INSTANCE).remove(key); } catch (Exception ignore) {}
        }
        try { redissonClient.getScoredSortedSet(globalTimeIndexKey(), StringCodec.INSTANCE).remove(key); } catch (Exception ignore) {}
    }

    private String topicIndexKey(String topic) { return controlPrefix + ":payload:idx:" + topic; }
    private String globalTimeIndexKey() { return controlPrefix + ":payload:ts"; }

    // Extract topic between "{controlPrefix}:payload:" and ":p:"
    private String parseTopicFromPayloadKey(String key) {
        String prefix = payloadPrefix + ":"; // {controlPrefix}:payload:
        if (key == null || !key.startsWith(prefix)) return null;
        int mid = key.indexOf(":p:", prefix.length());
        if (mid < 0) return null;
        return key.substring(prefix.length(), mid);
    }
}
