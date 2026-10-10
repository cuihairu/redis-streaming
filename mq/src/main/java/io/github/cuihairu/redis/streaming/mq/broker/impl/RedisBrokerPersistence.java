package io.github.cuihairu.redis.streaming.mq.broker.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.broker.BrokerPersistence;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.impl.StreamEntryCodec;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;

import java.util.Map;

/**
 * Redis Streams based persistence for Broker.
 */
public class RedisBrokerPersistence implements BrokerPersistence {

    private final RedissonClient redissonClient;
    private final TopicRegistry topicRegistry;
    private final MqOptions options;
    private final StreamKeys keys;
    // MQ-13: hard-cap rescue deletes only need entry IDS, so the range scan is paged with
    // a bounded count instead of materializing the whole backlog (values included) in one
    // range(Integer.MAX_VALUE-ish) call. Read per instance (not static) so tests can tune
    // it for a fresh instance even when the class was already loaded with the default.
    private final int hardCapPageSize = Math.max(1, Integer.getInteger("mq.retention.test.hardCapPageSize", 500));

    public RedisBrokerPersistence(RedissonClient redissonClient, MqOptions options) {
        this.redissonClient = redissonClient;
        this.options = options == null ? MqOptions.builder().build() : options;
        this.keys = StreamKeys.of(this.options);
        this.topicRegistry = new TopicRegistry(redissonClient, this.keys);
    }

    @Override
    public String append(String topic, int partitionId, Message message) {
        if (topic == null || topic.trim().isEmpty()) {
            return null;
        }
        if (message == null) {
            return null;
        }

        // Production rate quota (tenant:topic bucket); a rejection fails fast, never blocks
        if (!io.github.cuihairu.redis.streaming.mq.config.SendQuota.tryAcquire(
                options.getSendQuota(), keys.getTenant(), topic)) {
            throw new io.github.cuihairu.redis.streaming.mq.config.SendRateLimitedException(keys.getTenant(), topic);
        }

        // Ensure topic keyspace exists (compat with current behavior)
        topicRegistry.registerTopic(topic);
        String streamKey = keys.partitionStreamKey(topic, partitionId);
        Map<String, Object> data = StreamEntryCodec.buildPartitionEntry(message, partitionId,
                new io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager(redissonClient, options));

        // Normalize entry values to strings for XADD via Lua and to be codec-agnostic.
        // Especially important for complex objects like payload/headers when global codec is StringCodec.
        java.util.Map<String, Object> serialized = new java.util.HashMap<>(data.size());
        com.fasterxml.jackson.databind.ObjectMapper _om = new com.fasterxml.jackson.databind.ObjectMapper();
        for (java.util.Map.Entry<String, Object> e : data.entrySet()) {
            Object v = e.getValue();
            String s;
            try {
                if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
                    s = String.valueOf(v == null ? "" : v);
                } else {
                    // JSON-encode non-primitive values (e.g., Map payload, headers) to preserve structure
                    s = _om.writeValueAsString(v);
                }
            } catch (Exception ex) {
                // Fallback to toString if JSON serialization fails
                s = String.valueOf(v);
            }
            serialized.put(e.getKey(), s);
        }
        // Prefer atomic XADD MAXLEN (= exact) to avoid concurrency race and ensure hard bound
        try {
            int maxLen = Math.max(0, options.getRetentionMaxLenPerPartition());
            if (maxLen > 0) {
                java.util.List<Object> argv = new java.util.ArrayList<>();
                argv.add(String.valueOf(maxLen));
                for (Map.Entry<String, Object> e : serialized.entrySet()) {
                    argv.add(e.getKey());
                    argv.add(e.getValue());
                }
                Object res = redissonClient.getScript().eval(
                        RScript.Mode.READ_WRITE,
                        // Use '=' for exact trimming semantics (Redis 7+). Without it Redis may approximate and exceed the bound.
                        "return redis.call('XADD', KEYS[1], 'MAXLEN', '=', ARGV[1], '*', unpack(ARGV, 2))",
                        RScript.ReturnType.VALUE,
                        java.util.Collections.singletonList(streamKey), argv.toArray());
                String sid = res != null ? String.valueOf(res) : null;
                try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordTrim(topic, partitionId, 0L, "maxlen"); } catch (Exception ignore) {}
                return sid;
            }
        } catch (Exception ignore) {
            // Fallback below
        }

        // Fallback: two-step add + trim (non-atomic)
        RStream<String, Object> stream = redissonClient.getStream(streamKey, org.redisson.client.codec.StringCodec.INSTANCE);
        // Use serialized (string) values to avoid codec-dependent object encoding
        StreamMessageId id = stream.add(StreamAddArgs.entries(serialized));
        String sid = id != null ? id.toString() : null;
        try {
            int maxLen = options.getRetentionMaxLenPerPartition();
            if (maxLen > 0) {
                // Prefer exact trimming when available (Redis 7+)
                try {
                    String lua = "return redis.call('XTRIM', KEYS[1], 'MAXLEN', '=', ARGV[1])";
                    redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.STRING,
                            java.util.Collections.singletonList(streamKey), String.valueOf(maxLen));
                } catch (Exception eExact) {
                    // Compatibility fallback for older Redis: approximate trimming (may exceed bound transiently)
                    try {
                        String luaApprox = "return redis.call('XTRIM', KEYS[1], 'MAXLEN', ARGV[1])";
                        redissonClient.getScript().eval(RScript.Mode.READ_WRITE, luaApprox, RScript.ReturnType.STRING,
                                java.util.Collections.singletonList(streamKey), String.valueOf(maxLen));
                    } catch (Exception ignore2) {}
                }
                // Enforce a hard cap if still above maxLen (delete oldest entries). This is a small exact trim
                // to satisfy strict tests even on Redis versions without '=' exact trimming.
                // MQ-13: page the id scan with a bounded count — the old single range(batch)
                // call materialized the entire backlog (values included) just to collect ids.
                try {
                    long size = stream.size();
                    if (size > maxLen) {
                        long toDelete = size - maxLen;
                        long removed = 0;
                        StreamMessageId cursor = StreamMessageId.MIN;
                        while (removed < toDelete) {
                            @SuppressWarnings("deprecation")
                            java.util.Map<StreamMessageId, java.util.Map<String, Object>> page =
                                    stream.range(hardCapPageSize, cursor, StreamMessageId.MAX);
                            if (page == null || page.isEmpty()) break;
                            StreamMessageId last = null;
                            for (StreamMessageId rid : page.keySet()) {
                                try { stream.remove(rid); removed++; } catch (Exception ignore) {}
                                last = rid;
                                if (removed >= toDelete) break;
                            }
                            if (removed >= toDelete || page.size() < hardCapPageSize || last == null) break;
                            StreamMessageId next = new StreamMessageId(last.getId0(), last.getId1() + 1);
                            boolean advanced = next.getId0() > cursor.getId0()
                                    || (next.getId0() == cursor.getId0() && next.getId1() > cursor.getId1());
                            if (!advanced) break; // page re-delivered without progress — terminate
                            cursor = next;
                        }
                        final long removedTotal = removed;
                        try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordTrim(topic, partitionId, removedTotal, "xdel"); } catch (Exception ignore) {}
                    }
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        return sid;
    }
}
