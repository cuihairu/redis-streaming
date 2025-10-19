package io.github.cuihairu.redis.streaming.mq.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.cuihairu.redis.streaming.mq.Message;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Centralizes mapping between Message and Redis Stream entry maps to avoid
 * scattering ad-hoc Map assembly/parsing logic across the codebase.
 */
public final class StreamEntryCodec {
    // Reuse a pre-configured ObjectMapper to keep JSON handling consistent (e.g., Java Time)
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private StreamEntryCodec() {}

    /** Build a partition stream entry map from a Message. */
    public static Map<String, Object> buildPartitionEntry(Message m, int partitionId) {
        // Disambiguate overloads by casting to the lifecycle-based variant
        return buildPartitionEntry(m, partitionId, (PayloadLifecycleManager) null);
    }

    /** Build a partition stream entry map from a Message with lifecycle manager for large payload storage. */
    public static Map<String, Object> buildPartitionEntry(Message m, int partitionId, PayloadLifecycleManager lifecycleManager) {
        Map<String, Object> data = new HashMap<>();

        // Handle payload - check if it's large and should be stored in hash
        Object payload = m.getPayload();
        Map<String, String> headers = new HashMap<>();
        if (m.getHeaders() != null) {
            headers.putAll(m.getHeaders());
        }
        int payloadSize = getPayloadSize(payload);
        headers.put(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE, String.valueOf(payloadSize));
        if (lifecycleManager != null && shouldStoreInHash(payload)) {
            // Store large payload via lifecycle manager
            String hashKey = lifecycleManager.storeLargePayload(m.getTopic(), partitionId, payload);
            // Store reference in headers and set payload to null
            headers.put(PayloadHeaders.PAYLOAD_HASH_REF, hashKey);
            headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
            data.put("payload", null);
        } else {
            // Store payload inline
            headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
            data.put("payload", payload);
        }

        Instant ts = m.getTimestamp() != null ? m.getTimestamp() : Instant.now();
        data.put("timestamp", ts.toString());
        data.put("retryCount", m.getRetryCount());
        data.put("maxRetries", m.getMaxRetries());
        data.put("topic", m.getTopic());
        data.put("partitionId", partitionId);
        if (m.getKey() != null) data.put("key", m.getKey());
        if (!headers.isEmpty()) data.put("headers", headers);
        return data;
    }

    /** Build a partition stream entry map from a Message with Redis client (backward compat). */
    public static Map<String, Object> buildPartitionEntry(Message m, int partitionId, RedissonClient redissonClient) {
        PayloadLifecycleManager plm = redissonClient != null ? new PayloadLifecycleManager(redissonClient) : null;
        return buildPartitionEntry(m, partitionId, plm);
    }

    /** Parse a partition entry map into a Message. */
    @SuppressWarnings("unchecked")
    public static Message parsePartitionEntry(String topic, String id, Map<String, Object> data) {
        // Disambiguate overloads by casting to the lifecycle-based variant
        return parsePartitionEntry(topic, id, data, (PayloadLifecycleManager) null);
    }

    /** Parse a partition entry map into a Message with lifecycle manager for large payload loading. */
    @SuppressWarnings("unchecked")
    public static Message parsePartitionEntry(String topic, String id, Map<String, Object> data, RedissonClient redissonClient) {
        return parsePartitionEntry(topic, id, data, redissonClient, null);
    }

    /** Parse a partition entry map into a Message with lifecycle manager for large payload loading. */
    @SuppressWarnings("unchecked")
    public static Message parsePartitionEntry(String topic, String id, Map<String, Object> data, PayloadLifecycleManager lifecycleManager) {
        return parsePartitionEntry(topic, id, data, null, lifecycleManager);
    }

    /**
     * Parse a partition entry map into a Message with Redis client for large payload loading.
     * Also provides access to PayloadLifecycleManager for cleanup operations.
     */
    @SuppressWarnings("unchecked")
    public static Message parsePartitionEntry(String topic, String id, Map<String, Object> data,
                                              RedissonClient redissonClient, PayloadLifecycleManager lifecycleManager) {
        Message m = new Message();
        m.setId(id);
        m.setTopic(topic);

        String timestampStr = toStringOrNull(data.get("timestamp"));
        m.setTimestamp(timestampStr != null ? Instant.parse(timestampStr) : Instant.now());

        m.setRetryCount(toInt(data.get("retryCount"), 0));
        m.setMaxRetries(toInt(data.get("maxRetries"), 3));

        String key = toStringOrNull(data.get("key"));
        if (key != null) m.setKey(key);

        Object headersVal = data.get("headers");
        Map<String, String> headers = new HashMap<>();
        if (headersVal instanceof Map) {
            headers.putAll((Map<String, String>) headersVal);
        } else if (headersVal instanceof String) {
            try {
                headers.putAll(MAPPER.readValue((String) headersVal, new TypeReference<Map<String, String>>(){}));
            } catch (Exception ignore) {}
        }

        // Handle payload - if headers indicate hash storage, or if a hash ref is present with empty/absent payload, try to load
        Object payload = data.get("payload");
        String storageType = headers.get(PayloadHeaders.PAYLOAD_STORAGE_TYPE);
        String hashRef = headers.get(PayloadHeaders.PAYLOAD_HASH_REF);

        boolean shouldLoadFromHash = (hashRef != null)
                && (PayloadHeaders.STORAGE_TYPE_HASH.equals(storageType)
                    || payload == null
                    || (payload instanceof String && ((String) payload).isEmpty()));

        if (shouldLoadFromHash && (lifecycleManager != null || redissonClient != null)) {
            if (lifecycleManager != null) {
                payload = lifecycleManager.loadPayload(hashRef);
            } else {
                payload = loadPayloadFromHash(redissonClient, hashRef);
            }
            // Remove payload-related headers from user-visible headers
            headers.remove(PayloadHeaders.PAYLOAD_HASH_REF);
            headers.remove(PayloadHeaders.PAYLOAD_STORAGE_TYPE);
            headers.remove(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE);
        }

        m.setPayload(payload);
        m.setHeaders(headers);
        return m;
    }

    /** Build a DLQ entry map from a Message. */
    public static Map<String, Object> buildDlqEntry(Message m) {
        // Disambiguate overloads by casting to the lifecycle-based variant
        return buildDlqEntry(m, (PayloadLifecycleManager) null);
    }

    /** Build a DLQ entry map from a Message with lifecycle manager for large payload storage. */
    public static Map<String, Object> buildDlqEntry(Message m, RedissonClient redissonClient) {
        PayloadLifecycleManager plm = redissonClient != null ? new PayloadLifecycleManager(redissonClient) : null;
        return buildDlqEntry(m, plm);
    }

    /** Build a DLQ entry map from a Message with lifecycle manager for large payload storage. */
    public static Map<String, Object> buildDlqEntry(Message m, PayloadLifecycleManager lifecycleManager) {
        Map<String, Object> dlqData = new HashMap<>();
        dlqData.put("originalTopic", m.getTopic());

        // Handle payload - check if it's large and should be stored in hash
        Object payload = m.getPayload();
        Map<String, String> headers = new HashMap<>();
        if (m.getHeaders() != null) {
            headers.putAll(m.getHeaders());
        }

        if (lifecycleManager != null && shouldStoreInHash(payload)) {
            // Store large payload for DLQ via lifecycle manager
            String hashKey = lifecycleManager.storeLargePayload("dlq:" + m.getTopic(), 0, payload);

            // Store reference in headers
            headers.put(PayloadHeaders.PAYLOAD_HASH_REF, hashKey);
            headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
            headers.put(PayloadHeaders.PAYLOAD_ORIGINAL_SIZE, String.valueOf(getPayloadSize(payload)));
            dlqData.put("payload", null);
        } else {
            // Store payload inline
            headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
            dlqData.put("payload", payload);
        }

        Instant ts = m.getTimestamp() != null ? m.getTimestamp() : Instant.now();
        dlqData.put("timestamp", ts.toString());
        dlqData.put("failedAt", Instant.now().toString());
        dlqData.put("retryCount", m.getRetryCount());
        // best-effort partitionId from headers if present
        int pid = toInt(headers.get(io.github.cuihairu.redis.streaming.mq.MqHeaders.PARTITION_ID), 0);
        dlqData.put("partitionId", pid);
        if (m.getKey() != null) dlqData.put("key", m.getKey());
        if (!headers.isEmpty()) dlqData.put("headers", headers);
        if (m.getId() != null) dlqData.put("originalMessageId", m.getId());
        return dlqData;
    }

    /** Parse a DLQ entry map into a Message with minimal normalization. */
    public static Message parseDlqEntry(String id, Map<String, Object> data) {
        Message m = new Message();
        m.setId(id);
        String topic = toStringOrNull(data.get("originalTopic"));
        m.setTopic(topic != null ? topic : "");
        m.setPayload(data.get("payload"));
        String ts = toStringOrNull(data.get("timestamp"));
        m.setTimestamp(ts != null ? Instant.parse(ts) : Instant.now());

        m.setRetryCount(toInt(data.get("retryCount"), 0));

        // Merge known DLQ fields into headers for convenience
        Map<String, String> headers = new HashMap<>();
        if (topic != null) headers.put("originalTopic", topic);
        int pid = toInt(data.get("partitionId"), -1);
        if (pid >= 0) headers.put("partitionId", Integer.toString(pid));
        String key = toStringOrNull(data.get("key"));
        if (key != null) headers.put("key", key);

        Object hdr = data.get("headers");
        if (hdr instanceof Map) {
            try { headers.putAll(MAPPER.convertValue(hdr, new TypeReference<Map<String,String>>(){})); } catch (Exception ignore) {}
        } else if (hdr instanceof String) {
            try { headers.putAll(MAPPER.readValue((String) hdr, new TypeReference<Map<String,String>>(){})); } catch (Exception ignore) {}
        }
        m.setHeaders(headers);
        return m;
    }

    /** Build a partition stream entry from a DLQ entry (for replay). */
    public static Map<String, Object> buildPartitionEntryFromDlq(Map<String, Object> dlq, String topic, int partitionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", dlq.get("payload"));
        data.put("timestamp", Instant.now().toString());
        data.put("retryCount", 0);
        int maxRetries = toInt(dlq.get("maxRetries"), 3);
        data.put("maxRetries", maxRetries);
        data.put("topic", topic);
        data.put("partitionId", partitionId);
        Object key = dlq.get("key"); if (key != null) data.put("key", key);
        Object hdr = dlq.get("headers"); if (hdr != null) data.put("headers", hdr);
        return data;
    }

    private static int toInt(Object v, int def) {
        try {
            if (v == null) return def;
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) { return def; }
    }

    private static String toStringOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * Check if payload should be stored in Redis hash instead of inline
     */
    private static boolean shouldStoreInHash(Object payload) {
        return getPayloadSize(payload) > PayloadHeaders.MAX_INLINE_PAYLOAD_SIZE;
    }

    /**
     * Get the size of the payload in bytes
     */
    private static int getPayloadSize(Object payload) {
        if (payload == null) return 0;
        try {
            String json = MAPPER.writeValueAsString(payload);
            return json.getBytes("UTF-8").length;
        } catch (Exception e) {
            // fallback to string length estimation
            return payload.toString().length() * 2; // rough estimation for UTF-8
        }
    }

    /**
     * Generate a unique hash key for storing large payload
     */
    private static String generatePayloadHashKey(String topic, int partitionId) {
        return io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.controlPrefix()
                + ":payload:" + topic + ":p:" + partitionId + ":" + UUID.randomUUID();
    }

    /**
     * Store payload in Redis hash
     */
    private static void storePayloadInHash(RedissonClient redissonClient, String hashKey, Object payload) {
        try {
            String jsonPayload = MAPPER.writeValueAsString(payload);
            redissonClient.getBucket(hashKey, org.redisson.client.codec.StringCodec.INSTANCE).set(jsonPayload);
            // Set expiry - payload should expire after some time to avoid memory leaks
            redissonClient.getBucket(hashKey, org.redisson.client.codec.StringCodec.INSTANCE).expire(java.time.Duration.ofHours(24));
        } catch (Exception e) {
            throw new RuntimeException("Failed to store large payload in hash: " + hashKey, e);
        }
    }

    /**
     * Load payload from Redis hash
     */
    private static Object loadPayloadFromHash(RedissonClient redissonClient, String hashKey) {
        try {
            String jsonPayload = (String) redissonClient.getBucket(hashKey, org.redisson.client.codec.StringCodec.INSTANCE).get();
            if (jsonPayload == null) {
                throw new RuntimeException("Payload not found in hash: " + hashKey);
            }
            return MAPPER.readValue(jsonPayload, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load payload from hash: " + hashKey, e);
        }
    }
}
