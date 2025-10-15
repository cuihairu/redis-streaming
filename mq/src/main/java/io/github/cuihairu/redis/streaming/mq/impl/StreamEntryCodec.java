package io.github.cuihairu.redis.streaming.mq.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cuihairu.redis.streaming.mq.Message;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralizes mapping between Message and Redis Stream entry maps to avoid
 * scattering ad-hoc Map assembly/parsing logic across the codebase.
 */
public final class StreamEntryCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StreamEntryCodec() {}

    /** Build a partition stream entry map from a Message. */
    public static Map<String, Object> buildPartitionEntry(Message m, int partitionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", m.getPayload());
        Instant ts = m.getTimestamp() != null ? m.getTimestamp() : Instant.now();
        data.put("timestamp", ts.toString());
        data.put("retryCount", m.getRetryCount());
        data.put("maxRetries", m.getMaxRetries());
        data.put("topic", m.getTopic());
        data.put("partitionId", partitionId);
        if (m.getKey() != null) data.put("key", m.getKey());
        if (m.getHeaders() != null && !m.getHeaders().isEmpty()) data.put("headers", m.getHeaders());
        return data;
    }

    /** Parse a partition entry map into a Message. */
    @SuppressWarnings("unchecked")
    public static Message parsePartitionEntry(String topic, String id, Map<String, Object> data) {
        Message m = new Message();
        m.setId(id);
        m.setTopic(topic);
        m.setPayload(data.get("payload"));

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
        m.setHeaders(headers);
        return m;
    }

    /** Build a DLQ entry map from a Message. */
    public static Map<String, Object> buildDlqEntry(Message m) {
        Map<String, Object> dlqData = new HashMap<>();
        dlqData.put("originalTopic", m.getTopic());
        dlqData.put("payload", m.getPayload());
        Instant ts = m.getTimestamp() != null ? m.getTimestamp() : Instant.now();
        dlqData.put("timestamp", ts.toString());
        dlqData.put("failedAt", Instant.now().toString());
        dlqData.put("retryCount", m.getRetryCount());
        // best-effort partitionId from headers if present
        int pid = toInt(m.getHeaders() != null ? m.getHeaders().get("partitionId") : null, 0);
        dlqData.put("partitionId", pid);
        if (m.getKey() != null) dlqData.put("key", m.getKey());
        if (m.getHeaders() != null && !m.getHeaders().isEmpty()) dlqData.put("headers", m.getHeaders());
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
}

