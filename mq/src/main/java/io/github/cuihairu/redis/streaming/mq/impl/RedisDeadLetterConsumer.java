package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.Message;
import io.github.cuihairu.redis.streaming.mq.MessageConsumer;
import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dead Letter Queue consumer for a logical topic. Reads from single DLQ stream: stream:topic:{t}:dlq
 */
@Slf4j
public class RedisDeadLetterConsumer implements MessageConsumer {

    private final RedissonClient redissonClient;
    private final String consumerName;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    // Non-group offset per topic for plain XREAD fallback (or primary) path
    private final Map<String, StreamMessageId> lastIds = new ConcurrentHashMap<>();

    public RedisDeadLetterConsumer(RedissonClient redissonClient, String consumerName) {
        this.redissonClient = redissonClient;
        this.consumerName = consumerName;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void subscribe(String topic, MessageHandler handler) {
        subscribe(topic, "dlq-group", handler);
    }

    @Override
    public void subscribe(String topic, String consumerGroup, MessageHandler handler) {
        if (closed.get()) throw new IllegalStateException("Consumer is closed");
        String dlqKey = StreamKeys.dlq(topic);
        try {
            // Create group at MIN to consume existing entries (DLQ often has backlog)
            redissonClient.getStream(dlqKey)
                    .createGroup(StreamCreateGroupArgs.name(consumerGroup).id(StreamMessageId.MIN).makeStream());
        } catch (Exception ignore) {}
        subs.put(topic, new Sub(topic, consumerGroup, handler));
        log.info("Subscribed DLQ: topic='{}', group='{}', consumer='{}'", topic, consumerGroup, consumerName);
    }

    @Override
    public void unsubscribe(String topic) {
        subs.remove(topic);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting DLQ consumer '{}'", consumerName);
            executor.submit(this::loop);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping DLQ consumer '{}'", consumerName);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stop();
            executor.shutdown();
            try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            subs.clear();
        }
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public boolean isClosed() { return closed.get(); }

    private void loop() {
        while (running.get() && !closed.get()) {
            try {
                for (Sub s : subs.values()) {
                    String dlq = StreamKeys.dlq(s.topic);
                    RStream<String, Object> stream = redissonClient.getStream(dlq);
                    // First try plain XREAD style (non-group) to avoid subtle group semantics
                    StreamMessageId last = lastIds.getOrDefault(s.topic, StreamMessageId.MIN);
                    Map<StreamMessageId, Map<String, Object>> polled = stream.read(
                            org.redisson.api.stream.StreamReadArgs.greaterThan(last).count(10).timeout(Duration.ofMillis(500))
                    );
                    if (polled != null && !polled.isEmpty()) {
                        StreamMessageId maxId = last;
                        for (Map.Entry<StreamMessageId, Map<String, Object>> e : polled.entrySet()) {
                            StreamMessageId id = e.getKey();
                            Map<String, Object> data = e.getValue();
                            Message m = fromDlqData(id.toString(), data);
                            try {
                                MessageHandleResult r = s.handler.handle(m);
                                // Remove the entry after processing to avoid reprocessing
                                try { stream.remove(id); } catch (Exception ignore) {}
                                if (r == MessageHandleResult.RETRY) {
                                    // Replay to original topic partition if available
                                    try {
                                        String topic = (String) m.getHeaders().getOrDefault("originalTopic", m.getTopic());
                                        int pid = 0;
                                        Object pidVal = m.getHeaders().get("partitionId");
                                        if (pidVal != null) {
                                            try { pid = Integer.parseInt(pidVal.toString()); } catch (Exception ignore) {}
                                        }
                                        String skey = StreamKeys.partitionStream(topic, pid);
                                        RStream<String, Object> s2 = redissonClient.getStream(skey);
                                        Map<String, Object> d = new HashMap<>();
                                        d.put("payload", m.getPayload());
                                        d.put("timestamp", Instant.now().toString());
                                        d.put("retryCount", 0);
                                        d.put("maxRetries", m.getMaxRetries());
                                        d.put("topic", topic);
                                        d.put("partitionId", pid);
                                        s2.add(StreamAddArgs.entries(d));
                                    } catch (Exception ex2) {
                                        log.error("DLQ replay failed (plain)", ex2);
                                    }
                                }
                            } catch (Exception ex) {
                                log.error("DLQ handler error (plain) for {}", id, ex);
                                try { stream.remove(id); } catch (Exception ignore) {}
                            }
                            // track last seen id (polled entries are strictly > last by contract)
                            maxId = id;
                        }
                        lastIds.put(s.topic, maxId);
                        continue;
                    }

                    // If plain read yields nothing, use group path (if user prefers groups)
                    try {
                        stream.createGroup(StreamCreateGroupArgs.name(s.group).id(StreamMessageId.MIN).makeStream());
                    } catch (Exception ignore) {}
                    Map<StreamMessageId, Map<String, Object>> messages = stream.readGroup(
                            s.group, consumerName, StreamReadGroupArgs.neverDelivered().count(10).timeout(Duration.ofMillis(500))
                    );
                    for (Map.Entry<StreamMessageId, Map<String, Object>> e : messages.entrySet()) {
                        StreamMessageId id = e.getKey();
                        Map<String, Object> data = e.getValue();
                        Message m = fromDlqData(id.toString(), data);
                        try {
                            MessageHandleResult r = s.handler.handle(m);
                            handleResult(stream, s.group, id, m, r);
                        } catch (Exception ex) {
                            log.error("DLQ handler error for {}", id, ex);
                            // ack to avoid poison; user can requeue explicitly via admin
                            stream.ack(s.group, id);
                        }
                    }
                }
            } catch (Exception e) {
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private void handleResult(RStream<String, Object> stream, String group, StreamMessageId id, Message m, MessageHandleResult r) {
        switch (r) {
            case SUCCESS:
                stream.ack(group, id);
                break;
            case RETRY:
                // Replay to original topic partition if available, then ack
                try {
                    String topic = (String) m.getHeaders().getOrDefault("originalTopic", m.getTopic());
                    int pid = 0;
                    Object pidVal = m.getHeaders().get("partitionId");
                    if (pidVal != null) {
                        try { pid = Integer.parseInt(pidVal.toString()); } catch (Exception ignore) {}
                    }
                    String skey = StreamKeys.partitionStream(topic, pid);
                    RStream<String, Object> s = redissonClient.getStream(skey);
                    Map<String, Object> d = new HashMap<>();
                    d.put("payload", m.getPayload());
                    d.put("timestamp", Instant.now().toString());
                    d.put("retryCount", 0);
                    d.put("maxRetries", m.getMaxRetries());
                    d.put("topic", topic);
                    d.put("partitionId", pid);
                    s.add(StreamAddArgs.entries(d));
                } catch (Exception ex) {
                    log.error("DLQ replay failed", ex);
                } finally {
                    stream.ack(group, id);
                }
                break;
            case FAIL:
            case DEAD_LETTER:
                // Ack to drop
                stream.ack(group, id);
                break;
        }
    }

    @SuppressWarnings("unchecked")
    private Message fromDlqData(String id, Map<String, Object> data) {
        Message m = new Message();
        m.setId(id);
        String topic = (String) data.getOrDefault("originalTopic", "");
        m.setTopic(topic);
        m.setPayload(data.get("payload"));
        String ts = (String) data.get("timestamp");
        m.setTimestamp(ts != null ? Instant.parse(ts) : Instant.now());
        Object rc = data.get("retryCount");
        if (rc instanceof Number) m.setRetryCount(((Number) rc).intValue());
        Object pid = data.get("partitionId");
        Map<String, String> headers = new HashMap<>();
        headers.put("originalTopic", topic);
        if (pid instanceof Number) headers.put("partitionId", Integer.toString(((Number) pid).intValue()));
        String key = (String) data.get("key"); if (key != null) headers.put("key", key);
        Object hdr = data.get("headers"); if (hdr instanceof Map) headers.putAll((Map<String, String>) hdr);
        m.setHeaders(headers);
        return m;
    }

    private static class Sub {
        final String topic; // original topic
        final String group;
        final MessageHandler handler;
        Sub(String t, String g, MessageHandler h){ this.topic=t; this.group=g; this.handler=h; }
    }
}
