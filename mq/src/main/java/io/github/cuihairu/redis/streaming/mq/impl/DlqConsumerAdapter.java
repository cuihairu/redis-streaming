package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterConsumer;
import io.github.cuihairu.redis.streaming.reliability.dlq.DeadLetterEntry;
import io.github.cuihairu.redis.streaming.reliability.dlq.RedisDeadLetterConsumer;
import org.redisson.api.RedissonClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Adapter to expose reliability DLQ consumer as MQ MessageConsumer. */
public class DlqConsumerAdapter implements MessageConsumer {
    private final DeadLetterConsumer delegate;
    private final org.redisson.api.RedissonClient client;
    private static final com.fasterxml.jackson.databind.ObjectMapper _om = new com.fasterxml.jackson.databind.ObjectMapper();

    public DlqConsumerAdapter(RedissonClient client, String consumerName, MqOptions options) {
        this.client = client;
        // Provide a robust replay handler: publish back to original partition using StringCodec
        io.github.cuihairu.redis.streaming.reliability.dlq.ReplayHandler replay = (topic, partitionId, payload, headers, maxRetries) -> {
            try {
                String key = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, partitionId);
                org.redisson.api.RStream<String, Object> p = client.getStream(key, org.redisson.client.codec.StringCodec.INSTANCE);
                java.util.Map<String,Object> d = new java.util.HashMap<>();
                // Normalize values to strings for StringCodec/stream fields
                d.put("payload", (payload instanceof String) ? payload : toJson(payload));
                d.put("timestamp", java.time.Instant.now().toString());
                d.put("retryCount", 0);
                d.put("maxRetries", Math.max(1, maxRetries));
                d.put("topic", topic);
                d.put("partitionId", partitionId);
                if (headers != null && !headers.isEmpty()) d.put("headers", toJson(headers));
                return p.add(org.redisson.api.stream.StreamAddArgs.entries(d)) != null;
            } catch (Exception e) { return false; }
        };
        this.delegate = new RedisDeadLetterConsumer(client, consumerName,
                options != null ? options.getDefaultDlqGroup() : "dlq-group",
                replay);
        // configure DLQ stream prefix to match MQ if needed
        io.github.cuihairu.redis.streaming.reliability.dlq.DlqKeys.configure(
                options != null ? options.getStreamKeyPrefix() : "stream:topic");
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        delegate.subscribe(topic, entry -> toResult(handler, entry));
    }

    @Override
    public void subscribe(String topic, String group, MessageHandler handler) {
        delegate.subscribe(topic, group, entry -> toResult(handler, entry));
    }

    private DeadLetterConsumer.HandleResult toResult(MessageHandler handler, DeadLetterEntry e) throws Exception {
        Message m = new Message();
        m.setId(e.getId());
        m.setTopic(e.getOriginalTopic());
        m.setPayload(e.getPayload());
        m.setTimestamp(e.getTimestamp()!=null?e.getTimestamp(): Instant.now());
        m.setRetryCount(e.getRetryCount());
        m.setMaxRetries(e.getMaxRetries());
        Map<String,String> hdr = new HashMap<>();
        if (e.getHeaders()!=null) hdr.putAll(e.getHeaders());
        hdr.put("partitionId", Integer.toString(e.getPartitionId()));
        m.setHeaders(hdr);
        MessageHandleResult r = handler.handle(m);
        switch (r) {
            case SUCCESS:
                return DeadLetterConsumer.HandleResult.SUCCESS;
            case RETRY: {
                // Proactively replay once here to ensure visibility even if delegate replay path is delayed
                try {
                    int pid = e.getPartitionId();
                    String topic = e.getOriginalTopic();
                    String key = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, pid);
                    org.redisson.api.RStream<String, Object> p = client.getStream(key, org.redisson.client.codec.StringCodec.INSTANCE);
                    java.util.Map<String,Object> d = new java.util.HashMap<>();
                    d.put("payload", (e.getPayload() instanceof String) ? e.getPayload() : toJson(e.getPayload()));
                    d.put("timestamp", java.time.Instant.now().toString());
                    d.put("retryCount", 0);
                    d.put("maxRetries", Math.max(1, e.getMaxRetries()));
                    d.put("topic", topic);
                    d.put("partitionId", pid);
                    if (e.getHeaders() != null && !e.getHeaders().isEmpty()) d.put("headers", toJson(e.getHeaders()));
                    p.add(org.redisson.api.stream.StreamAddArgs.entries(d));
                } catch (Exception ignore) {}
                return DeadLetterConsumer.HandleResult.RETRY;
            }
            case FAIL:
            case DEAD_LETTER:
                return DeadLetterConsumer.HandleResult.FAIL;
            default:
                return DeadLetterConsumer.HandleResult.FAIL;
        }
    }

    @Override public void unsubscribe(String topic) { /* not exposed by delegate; no-op */ }
    @Override public void start() { delegate.start(); }
    @Override public void stop() { delegate.stop(); }
    @Override public void close() { delegate.close(); }
    @Override public boolean isRunning() { return delegate.isRunning(); }
    @Override public boolean isClosed() { return delegate.isClosed(); }

    private static String toJson(Object o) {
        try { return _om.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
}
