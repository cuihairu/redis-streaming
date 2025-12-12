package io.github.cuihairu.redis.streaming.reliability.dlq;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;

import java.util.Map;

/**
 * Redis implementation of DeadLetterService using Redis Streams.
 * Note: replay currently writes directly to the original partition stream key for compatibility
 *       (keeps existing behavior); can be evolved to use MQ Producer via injection.
 */
@Slf4j
public class RedisDeadLetterService implements DeadLetterService {
    private final RedissonClient redissonClient;
    private final ReplayHandler replayHandler;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    public RedisDeadLetterService(RedissonClient redissonClient) { this(redissonClient, null); }

    public RedisDeadLetterService(RedissonClient redissonClient, ReplayHandler replayHandler) {
        this.redissonClient = redissonClient;
        this.replayHandler = replayHandler;
    }

    @Override
    public StreamMessageId send(DeadLetterRecord record) {
        String key = DlqKeys.dlq(record.originalTopic);
        RStream<String, Object> dlq = redissonClient.getStream(key);
        Map<String, Object> entry = DeadLetterCodec.buildEntry(record);
        return dlq.add(StreamAddArgs.entries(entry));
    }

    @Override
    public Map<StreamMessageId, Map<String, Object>> range(String originalTopic, int limit) {
        String key = DlqKeys.dlq(originalTopic);
        RStream<String, Object> dlq = redissonClient.getStream(key);
        try {
            @SuppressWarnings("deprecation")
            Map<StreamMessageId, Map<String, Object>> result = dlq.range(limit, StreamMessageId.MIN, StreamMessageId.MAX);
            return result;
        } catch (Exception e) {
            log.error("Failed to range DLQ: {}", key, e);
            return Map.of();
        }
    }

    @Override
    public long size(String originalTopic) {
        String key = DlqKeys.dlq(originalTopic);
        try {
            long sz = redissonClient.getStream(key).size();
            if (sz == 0) {
                @SuppressWarnings({"deprecation", "unchecked"})
                Map<StreamMessageId, Map<String, Object>> any = (Map) redissonClient.getStream(key).range(1, StreamMessageId.MIN, StreamMessageId.MAX);
                return (any == null || any.isEmpty()) ? 0 : any.size();
            }
            return sz;
        } catch (Exception e) {
            log.error("Failed to get DLQ size: {}", key, e);
            return 0;
        }
    }

    @Override
    public boolean delete(String originalTopic, StreamMessageId id) {
        String key = DlqKeys.dlq(originalTopic);
        try {
            long deleted = redissonClient.getStream(key).remove(id);
            boolean ok = deleted > 0;
            if (ok) io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics.get().incDlqDelete(originalTopic);
            return ok;
        } catch (Exception e) {
            log.error("Failed to delete DLQ entry: {} id={} ", key, id, e);
            return false;
        }
    }

    @Override
    public long clear(String originalTopic) {
        String key = DlqKeys.dlq(originalTopic);
        try {
            long size = redissonClient.getStream(key).size();
            boolean ok = redissonClient.getKeys().delete(key) > 0;
            if (ok) io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics.get().incDlqClear(originalTopic, size);
            return ok ? size : 0;
        } catch (Exception e) {
            log.error("Failed to clear DLQ: {}", key, e);
            return 0;
        }
    }

    @Override
    public boolean replay(String originalTopic, StreamMessageId id) {
        String dlqKey = DlqKeys.dlq(originalTopic);
        try {
            long start = System.nanoTime();
            RStream<String, Object> dlq = redissonClient.getStream(dlqKey);
            @SuppressWarnings("deprecation")
            Map<StreamMessageId, Map<String, Object>> msgs = dlq.range(1, id, id);
            if (msgs.isEmpty()) return false;
            Map<String, Object> data = msgs.get(id);

            int pid = 0;
            Object pidVal = data.get("partitionId");
            if (pidVal instanceof Number) pid = ((Number) pidVal).intValue();
            else if (pidVal != null) { try { pid = Integer.parseInt(pidVal.toString()); } catch (Exception ignore) {} }

            if (replayHandler != null) {
                java.util.Map<String,String> headers = new java.util.HashMap<>();
                Object hdr = data.get("headers");
                if (hdr instanceof java.util.Map) {
                    ((java.util.Map<?,?>) hdr).forEach((k,v) -> { if (k!=null && v!=null) headers.put(String.valueOf(k), String.valueOf(v)); });
                } else if (hdr instanceof String) {
                    try {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String,Object> m = MAPPER.readValue((String) hdr,
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>(){});
                        if (m != null) m.forEach((k,v) -> { if (k!=null && v!=null) headers.put(String.valueOf(k), String.valueOf(v)); });
                    } catch (Exception ignore) {}
                }
                int maxRetries = 3;
                Object mr = data.get("maxRetries");
                try { if (mr != null) maxRetries = (mr instanceof Number) ? ((Number) mr).intValue() : Integer.parseInt(String.valueOf(mr)); } catch (Exception ignore) {}

                // If DLQ stored payload via hash reference, resolve it for the custom replay handler.
                Object payload = data.get("payload");
                String storageType = headers.get("x-payload-storage-type");
                String ref = headers.get("x-payload-hash-ref");
                if ((payload == null || (payload instanceof String && ((String) payload).isEmpty()))
                        && "hash".equals(storageType) && ref != null && !ref.isEmpty()) {
                    try {
                        org.redisson.api.RBucket<String> b = redissonClient.getBucket(ref, StringCodec.INSTANCE);
                        String json = b.get();
                        if (json != null) {
                            payload = MAPPER.readValue(json, Object.class);
                        } else {
                            headers.put("x-payload-missing", "true");
                            headers.put("x-payload-missing-ref", ref);
                        }
                    } catch (Exception ignore) {}
                }

                boolean ok = replayHandler.publish(originalTopic, pid, payload, headers, maxRetries);
                long dur = System.nanoTime() - start;
                io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics.get()
                        .recordDlqReplay(originalTopic, pid, ok, dur);
                return ok;
            } else {
                // direct write fallback with basic TTL refresh for hash payload reference when present
                String streamKey = ("stream:topic" + ":" + originalTopic + ":p:" + pid);
                RStream<String, Object> orig = redissonClient.getStream(streamKey);
                Map<String, Object> replay = DeadLetterCodec.buildPartitionEntryFromDlq(data, originalTopic, pid);

                // If DLQ entry references hash-stored payload, attempt to extend TTL on the referenced key
                try {
                    Object hdr = data.get("headers");
                    java.util.Map<String,String> headers = new java.util.HashMap<>();
                    if (hdr instanceof java.util.Map) {
                        ((java.util.Map<?,?>) hdr).forEach((k,v) -> { if (k!=null && v!=null) headers.put(String.valueOf(k), String.valueOf(v)); });
                    } else if (hdr instanceof String) {
                        try { headers.putAll(new com.fasterxml.jackson.databind.ObjectMapper().readValue((String) hdr, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,String>>(){})); } catch (Exception ignore) {}
                    }
                    String storageType = headers.get("x-payload-storage-type");
                    String ref = headers.get("x-payload-hash-ref");
                    if ("hash".equals(storageType) && ref != null && !ref.isEmpty()) {
                        org.redisson.api.RBucket<String> b = redissonClient.getBucket(ref, StringCodec.INSTANCE);
                        String val = b.get();
                        if (val != null) {
                            // extend TTL to a safe window (24h) to reduce chance of missing on immediate consume
                            b.set(val, java.time.Duration.ofHours(24));
                        } else {
                            // annotate missing for observability; payload consumer will DLQ accordingly
                            java.util.Map<String,String> h2 = new java.util.HashMap<>();
                            Object h0 = replay.get("headers");
                            if (h0 instanceof java.util.Map) {
                                ((java.util.Map<?,?>) h0).forEach((k,v) -> { if (k!=null && v!=null) h2.put(String.valueOf(k), String.valueOf(v)); });
                            }
                            h2.put("x-payload-missing", "true");
                            h2.put("x-payload-missing-ref", ref);
                            replay.put("headers", h2);
                        }
                    }
                } catch (Exception ignore) {}
                StreamMessageId nid = orig.add(StreamAddArgs.entries(replay));
                boolean ok = nid != null;
                long dur = System.nanoTime() - start;
                io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics.get()
                        .recordDlqReplay(originalTopic, pid, ok, dur);
                return ok;
            }
        } catch (Exception e) {
            log.error("Failed to replay DLQ entry: topic={}, id={}", originalTopic, id, e);
            io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics.get()
                    .recordDlqReplay(originalTopic, 0, false, 0);
            return false;
        }
    }
}
