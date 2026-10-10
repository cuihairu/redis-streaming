package io.github.cuihairu.redis.streaming.mq.dlq;

import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DLQ consumer. Reads from {streamPrefix}:{topic}:dlq and handles entries.
 */
@Slf4j
public class RedisDeadLetterConsumer implements DeadLetterConsumer {
    private final RedissonClient redissonClient;
    private final StreamKeys keys;
    private final String consumerName;
    private final String defaultGroup;
    private final ReplayHandler replayHandler;

    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    private final Map<String, StreamMessageId> lastIds = new ConcurrentHashMap<>();
    // MQ-01: pending sweep cadence — a PEL entry whose handler threw or whose replay
    // failed is otherwise never re-read (neverDelivered() only). Same convention as the
    // main consumer's processPendingMessages: claim idle entries and re-run the handler.
    // Read per instance (not static) so tests can tune them for a fresh consumer even
    // when the class was already loaded with defaults by an earlier test.
    private final Map<String, Long> lastSweepAt = new ConcurrentHashMap<>();
    private final long claimIdleMs = Long.getLong("mq.dlq.test.claimIdleMs", 300_000L);
    private final long pendingSweepMs = Long.getLong("mq.dlq.test.pendingSweepMs", 5_000L);
    private static final int PENDING_SWEEP_BATCH = 50;
    private static final com.fasterxml.jackson.databind.ObjectMapper _om = new com.fasterxml.jackson.databind.ObjectMapper();

    public RedisDeadLetterConsumer(RedissonClient redissonClient, String consumerName, String defaultGroup) {
        this(redissonClient, consumerName, defaultGroup, null);
    }

    public RedisDeadLetterConsumer(RedissonClient redissonClient, String consumerName, String defaultGroup, ReplayHandler replayHandler) {
        this(redissonClient, consumerName, defaultGroup, replayHandler, StreamKeys.shared());
    }

    /** Key view carrying the tenant segment, so DLQ keys match a tenant-scoped topic. */
    public RedisDeadLetterConsumer(RedissonClient redissonClient, String consumerName, String defaultGroup,
                                   ReplayHandler replayHandler, StreamKeys keys) {
        this.redissonClient = redissonClient;
        this.consumerName = consumerName;
        this.defaultGroup = (defaultGroup==null||defaultGroup.isBlank())?"dlq-group":defaultGroup;
        this.replayHandler = replayHandler;
        this.keys = keys == null ? StreamKeys.shared() : keys;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void subscribe(String topic, DeadLetterHandler handler) {
        subscribe(topic, defaultGroup, handler);
    }

    @Override
    public void subscribe(String topic, String group, DeadLetterHandler handler) {
        if (closed.get()) throw new IllegalStateException("Consumer is closed");
        String dlqKey = keys.dlqKey(topic);
        try {
            redissonClient.getStream(dlqKey)
                    .createGroup(StreamCreateGroupArgs.name(group).id(new StreamMessageId(0, 0)).makeStream());
        } catch (Exception ignore) {}
        subs.put(topic, new Sub(topic, group, handler));
        log.info("Subscribed DLQ: topic='{}', group='{}', consumer='{}'", topic, group, consumerName);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::loop);
        }
    }

    @Override
    public void stop() { running.set(false); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stop();
            executor.shutdown();
            try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            subs.clear();
        }
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isClosed() { return closed.get(); }

    private void loop() {
        while (running.get() && !closed.get()) {
            try {
                for (Sub s : subs.values()) {
                    String dlq = keys.dlqKey(s.topic);
                    RStream<String, Object> streamDefault = redissonClient.getStream(dlq);
                    RStream<String, Object> streamString  = redissonClient.getStream(dlq, org.redisson.client.codec.StringCodec.INSTANCE);
                    RStream<String, Object> stream = streamDefault;
                    StreamMessageId last = lastIds.getOrDefault(s.topic, StreamMessageId.MIN);

                    Map<StreamMessageId, Map<String, Object>> messages = java.util.Collections.emptyMap();
                    try {
                        streamDefault.createGroup(StreamCreateGroupArgs.name(s.group).id(new StreamMessageId(0, 0)).makeStream());
                    } catch (Exception ignore) {}
                    try {
                        messages = streamDefault.readGroup(s.group, consumerName,
                                StreamReadGroupArgs.neverDelivered().count(10).timeout(Duration.ofMillis(500)));
                        stream = streamDefault;
                    } catch (Exception ignore) {}
                    if (messages == null || messages.isEmpty()) {
                        try { streamString.createGroup(StreamCreateGroupArgs.name(s.group).id(new StreamMessageId(0, 0)).makeStream()); } catch (Exception ignore) {}
                        try {
                            messages = streamString.readGroup(s.group, consumerName,
                                    StreamReadGroupArgs.neverDelivered().count(10).timeout(Duration.ofMillis(500)));
                            if (messages != null && !messages.isEmpty()) stream = streamString;
                        } catch (Exception ignore) {}
                    }
                    if ((messages == null || messages.isEmpty()) && Boolean.getBoolean("mq.dlq.test.readAllIds")) {
                        try {
                            messages = streamDefault.readGroup(s.group, consumerName,
                                    StreamReadGroupArgs.greaterThan(StreamMessageId.MIN).count(10).timeout(Duration.ofMillis(200)));
                            stream = streamDefault;
                        } catch (Exception ignore) {}
                        if (messages == null || messages.isEmpty()) {
                            try {
                                messages = streamString.readGroup(s.group, consumerName,
                                        StreamReadGroupArgs.greaterThan(StreamMessageId.MIN).count(10).timeout(Duration.ofMillis(200)));
                                if (messages != null && !messages.isEmpty()) stream = streamString;
                            } catch (Exception ignore) {}
                        }
                    }
                    try { if (messages!=null && !messages.isEmpty()) log.info("DLQ group read: topic={}, codec=default, messages={}", s.topic, messages.size()); } catch (Exception ignore) {}
                    for (Map.Entry<StreamMessageId, Map<String, Object>> e : messages.entrySet()) {
                        processEntry(s, stream, e.getKey(), e.getValue());
                    }

                    // MQ-01: reclaim PEL entries the failure paths left behind (handler
                    // threw / replay failed) — neverDelivered() never returns them again
                    long nowMs = System.currentTimeMillis();
                    Long sweptAt = lastSweepAt.get(s.topic);
                    if (sweptAt == null || nowMs - sweptAt >= pendingSweepMs) {
                        lastSweepAt.put(s.topic, nowMs);
                        sweepPending(s, streamDefault, streamString);
                    }
                }
            } catch (Exception e) {
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    /**
     * Single entry disposition shared by the live read and the pending sweep (MQ-01):
     * handler SUCCESS/FAIL ack, RETRY acks only when the replay succeeded. A handler
     * throw or failed replay leaves the id pending — the sweep re-claims it after the
     * idle threshold, mirroring the main consumer's convention (unbounded retry).
     */
    private void processEntry(Sub s, RStream<String, Object> stream, StreamMessageId id, Map<String, Object> data) {
        DeadLetterEntry entry = DeadLetterCodec.parseEntry(id.toString(), data);
        try {
            long holdMs = Long.getLong("mq.dlq.test.holdBeforeHandleMs", 0L);
            if (holdMs > 0) { try { Thread.sleep(holdMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }
            DeadLetterConsumer.HandleResult r = s.handler.handle(entry);
            switch (r) {
                case SUCCESS:
                    stream.ack(s.group, id);
                    try { log.info("DLQ group SUCCESS: topic={}, id={}", s.topic, id); } catch (Exception ignore) {}
                    break;
                case RETRY: {
                    long start = System.nanoTime();
                    boolean ok = false;
                    try {
                        if (replayHandler != null) {
                            ok = replayHandler.publish(entry.getOriginalTopic(), entry.getPartitionId(), entry.getPayload(), entry.getHeaders(), entry.getMaxRetries());
                        } else {
                            String topic = entry.getOriginalTopic();
                            int pid = entry.getPartitionId();
                            RStream<String, Object> p = redissonClient.getStream(
                                    keys.partitionStreamKey(topic, pid),
                                    org.redisson.client.codec.StringCodec.INSTANCE);
                            Map<String, Object> d = DeadLetterCodec.buildPartitionEntryFromDlq(data, topic, pid);
                            p.add(StreamAddArgs.entries(d));
                            ok = true;
                            try {
                                boolean visible = p.isExists() && p.size() > 0;
                                if (!visible) { Thread.sleep(50); p.add(StreamAddArgs.entries(d)); }
                                try { log.info("DLQ group RETRY replay ok={}, origKey={}, visible={} size={}", ok, keys.partitionStreamKey(topic, pid), (p.isExists() && p.size()>0), p.size()); } catch (Exception ignore) {}
                            } catch (Exception ignore) {}
                        }
                    } catch (Exception ex) {
                        log.error("DLQ replay failed", ex);
                    } finally {
                        try {
                            MqMetrics.get()
                                    .recordDlqReplay(entry.getOriginalTopic(), entry.getPartitionId(), ok,
                                            System.nanoTime() - start);
                        } catch (Exception ignore) {}
                        if (ok) stream.ack(s.group, id);
                    }
                    break;
                }
                case FAIL:
                    stream.ack(s.group, id);
                    break;
            }
        } catch (Exception ex) {
            log.error("DLQ handler error for {}", id, ex);
        }
    }

    /**
     * MQ-01: re-claim idle PEL entries and run them through the same disposition as
     * live deliveries. Entries were written under two codecs historically; claiming
     * through the wrong one throws on decode — fall back to the other handle (the same
     * dance the read path does).
     */
    @SuppressWarnings("deprecation")
    private void sweepPending(Sub s, RStream<String, Object> streamDefault, RStream<String, Object> streamString) {
        try {
            java.util.List<org.redisson.api.stream.PendingEntry> pending = streamDefault.listPending(
                    s.group, StreamMessageId.MIN, StreamMessageId.MAX, PENDING_SWEEP_BATCH);
            for (org.redisson.api.stream.PendingEntry pe : pending) {
                if (pe.getIdleTime() < claimIdleMs) {
                    continue;
                }
                StreamMessageId id = pe.getId();
                if (claimAndProcess(s, streamDefault, id)) {
                    continue;
                }
                claimAndProcess(s, streamString, id);
            }
        } catch (Exception e) {
            log.error("DLQ pending sweep failed for topic {}", s.topic, e);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean claimAndProcess(Sub s, RStream<String, Object> stream, StreamMessageId id) {
        try {
            Map<StreamMessageId, Map<String, Object>> claimed = stream.claim(
                    s.group, consumerName, claimIdleMs, TimeUnit.MILLISECONDS, id);
            if (claimed == null || claimed.isEmpty()) {
                return false;
            }
            for (Map.Entry<StreamMessageId, Map<String, Object>> ce : claimed.entrySet()) {
                processEntry(s, stream, ce.getKey(), ce.getValue());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static class Sub {
        final String topic;
        final String group;
        final DeadLetterHandler handler;
        Sub(String t, String g, DeadLetterHandler h){ this.topic=t; this.group=g; this.handler=h; }
    }

    private static String toJson(Object o) {
        try { return _om.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }
}
