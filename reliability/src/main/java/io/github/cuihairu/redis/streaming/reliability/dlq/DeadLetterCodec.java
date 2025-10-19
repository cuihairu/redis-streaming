package io.github.cuihairu.redis.streaming.reliability.dlq;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Codec for DLQ entry maps to keep fields compatible with MQ format.
 */
final class DeadLetterCodec {

    private DeadLetterCodec() {}

    static Map<String, Object> buildEntry(DeadLetterRecord r) {
        Map<String, Object> m = new HashMap<>();
        m.put("originalTopic", r.originalTopic);
        m.put("payload", r.payload);
        m.put("timestamp", (r.timestamp != null ? r.timestamp : Instant.now()).toString());
        m.put("failedAt", Instant.now().toString());
        m.put("retryCount", r.retryCount);
        m.put("partitionId", r.originalPartition);
        if (r.headers != null && !r.headers.isEmpty()) m.put("headers", r.headers);
        if (r.originalMessageId != null) m.put("originalMessageId", r.originalMessageId);
        m.put("maxRetries", r.maxRetries);
        return m;
    }

    static Map<String, Object> buildPartitionEntryFromDlq(Map<String, Object> dlq, String topic, int partitionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", dlq.get("payload"));
        data.put("timestamp", Instant.now().toString());
        data.put("retryCount", 0);
        Object mr = dlq.get("maxRetries");
        int maxRetries = 3;
        try { if (mr != null) maxRetries = (mr instanceof Number) ? ((Number) mr).intValue() : Integer.parseInt(String.valueOf(mr)); } catch (Exception ignore) {}
        data.put("maxRetries", maxRetries);
        data.put("topic", topic);
        data.put("partitionId", partitionId);
        Object key = dlq.get("key"); if (key != null) data.put("key", key);
        Object hdr = dlq.get("headers"); if (hdr != null) data.put("headers", hdr);
        return data;
    }

    static DeadLetterEntry parseEntry(String id, Map<String,Object> data) {
        String topic = toStr(data.get("originalTopic"), "");
        int pid = toInt(data.get("partitionId"), 0);
        Object payload = data.get("payload");
        java.time.Instant ts;
        try {
            String tsStr = toStr(data.get("timestamp"), null);
            ts = tsStr != null ? java.time.Instant.parse(tsStr) : java.time.Instant.now();
        } catch (Exception e) { ts = java.time.Instant.now(); }
        int rc = toInt(data.get("retryCount"), 0);
        int mr = toInt(data.get("maxRetries"), 3);
        java.util.Map<String,String> headers = new java.util.HashMap<>();
        Object hdr = data.get("headers");
        if (hdr instanceof java.util.Map) {
            ((java.util.Map<?,?>) hdr).forEach((k,v) -> { if (k!=null && v!=null) headers.put(String.valueOf(k), String.valueOf(v)); });
        } else if (hdr instanceof String) {
            // Be tolerant: some producers store headers as JSON string
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String,Object> m = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue((String) hdr, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>(){});
                if (m != null) {
                    m.forEach((k,v) -> { if (k!=null && v!=null) headers.put(String.valueOf(k), String.valueOf(v)); });
                }
            } catch (Exception ignore) {}
        }
        if (topic != null) headers.put("originalTopic", topic);
        headers.put("partitionId", Integer.toString(pid));
        return new DeadLetterEntry(id, topic, pid, payload, headers, ts, rc, mr);
    }

    private static String toStr(Object v, String def) { return v==null?def:String.valueOf(v); }
    private static int toInt(Object v, int def) {
        try { if (v==null) return def; if (v instanceof Number) return ((Number)v).intValue(); return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }
}
