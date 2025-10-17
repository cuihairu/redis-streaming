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
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;

import java.util.Map;

/**
 * Redis Streams based persistence for Broker.
 */
public class RedisBrokerPersistence implements BrokerPersistence {

    private final RedissonClient redissonClient;
    private final TopicRegistry topicRegistry;
    private final MqOptions options;

    public RedisBrokerPersistence(RedissonClient redissonClient, MqOptions options) {
        this.redissonClient = redissonClient;
        this.options = options == null ? MqOptions.builder().build() : options;
        this.topicRegistry = new TopicRegistry(redissonClient, this.options.getKeyPrefix());
    }

    @Override
    public String append(String topic, int partitionId, Message message) {
        // Ensure topic keyspace exists (compat with current behavior)
        topicRegistry.registerTopic(topic);
        String streamKey = StreamKeys.partitionStream(topic, partitionId);
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
        // Prefer atomic XADD MAXLEN to avoid concurrency race
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
                        "return redis.call('XADD', KEYS[1], 'MAXLEN', ARGV[1], '*', unpack(ARGV, 2))",
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
        RStream<String, Object> stream = redissonClient.getStream(org.redisson.client.codec.StringCodec.INSTANCE, streamKey);
        // Use serialized (string) values to avoid codec-dependent object encoding
        StreamMessageId id = stream.add(StreamAddArgs.entries(serialized));
        String sid = id != null ? id.toString() : null;
        try {
            int maxLen = options.getRetentionMaxLenPerPartition();
            if (maxLen > 0) {
                String lua = "return redis.call('XTRIM', KEYS[1], 'MAXLEN', ARGV[1])";
                redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.STATUS,
                        java.util.Collections.singletonList(streamKey), String.valueOf(maxLen));
            }
        } catch (Exception ignore) {}
        return sid;
    }
}
