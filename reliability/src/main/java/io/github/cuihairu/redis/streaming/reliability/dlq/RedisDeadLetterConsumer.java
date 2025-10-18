package io.github.cuihairu.redis.streaming.reliability.dlq;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadArgs;
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
 * Reliability DLQ consumer. Reads from {streamPrefix}:{topic}:dlq and handles entries.
 * Plain (XREAD) and group paths are supported. Deletion occurs only on success or explicit drop.
 */
@Slf4j
public class RedisDeadLetterConsumer implements DeadLetterConsumer {
    private final RedissonClient redissonClient;
    private final String consumerName;
    private final String defaultGroup;
    private final ReplayHandler replayHandler;

    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    private final Map<String, StreamMessageId> lastIds = new ConcurrentHashMap<>();

    public RedisDeadLetterConsumer(RedissonClient redissonClient, String consumerName, String defaultGroup) {
        this(redissonClient, consumerName, defaultGroup, null);
    }

    public RedisDeadLetterConsumer(RedissonClient redissonClient, String consumerName, String defaultGroup, ReplayHandler replayHandler) {
        this.redissonClient = redissonClient;
        this.consumerName = consumerName;
        this.defaultGroup = (defaultGroup==null||defaultGroup.isBlank())?"dlq-group":defaultGroup;
        this.replayHandler = replayHandler;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void subscribe(String topic, DeadLetterHandler handler) {
        subscribe(topic, defaultGroup, handler);
    }

    @Override
    public void subscribe(String topic, String group, DeadLetterHandler handler) {
        if (closed.get()) throw new IllegalStateException("Consumer is closed");
        String dlqKey = DlqKeys.dlq(topic);
        try {
            redissonClient.getStream(dlqKey)
                    .createGroup(StreamCreateGroupArgs.name(group).id(StreamMessageId.MIN).makeStream());
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
                    String dlq = DlqKeys.dlq(s.topic);
                    // Use client's default codec to match writers that didn't normalize to strings
                    RStream<String, Object> stream = redissonClient.getStream(dlq);
                    StreamMessageId last = lastIds.getOrDefault(s.topic, StreamMessageId.MIN);
                    Map<StreamMessageId, Map<String, Object>> polled = stream.read(
                            StreamReadArgs.greaterThan(last).count(10).timeout(Duration.ofMillis(500))
                    );
                    if (polled != null && !polled.isEmpty()) {
                        StreamMessageId maxId = last;
                        for (Map.Entry<StreamMessageId, Map<String, Object>> e : polled.entrySet()) {
                            StreamMessageId id = e.getKey();
                            Map<String, Object> data = e.getValue();
                            DeadLetterEntry entry = DeadLetterCodec.parseEntry(id.toString(), data);
                            try {
                                DeadLetterConsumer.HandleResult r = s.handler.handle(entry);
                                switch (r) {
                                    case SUCCESS:
                                        try { stream.remove(id); } catch (Exception ignore) {}
                                        break;
                                    case RETRY: {
                                        boolean replayed = false;
                                        long start = System.nanoTime();
                                        try {
                                            if (replayHandler != null) {
                                                replayed = replayHandler.publish(entry.getOriginalTopic(), entry.getPartitionId(), entry.getPayload(), entry.getHeaders(), entry.getMaxRetries());
                                            } else {
                                                String topic = entry.getOriginalTopic();
                                                int pid = entry.getPartitionId();
                                                String skey = "stream:topic:" + topic + ":p:" + pid;
                                                RStream<String, Object> p = redissonClient.getStream(skey);
                                                Map<String, Object> d = new HashMap<>();
                                                d.put("payload", entry.getPayload());
                                                d.put("timestamp", Instant.now().toString());
                                                d.put("retryCount", 0);
                                                d.put("maxRetries", entry.getMaxRetries());
                                                d.put("topic", topic);
                                                d.put("partitionId", pid);
                                                StreamMessageId nid = p.add(StreamAddArgs.entries(d));
                                                replayed = nid != null;
                                            }
                                        } catch (Exception ex2) {
                                            log.error("DLQ replay failed (plain)", ex2);
                                        }
                                        try {
                                            io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics.get()
                                                    .recordDlqReplay(entry.getOriginalTopic(), entry.getPartitionId(), replayed,
                                                            System.nanoTime() - start);
                                        } catch (Exception ignore) {}
                                        if (replayed) { try { stream.remove(id); } catch (Exception ignore) {} }
                                        break;
                                    }
                                    case FAIL:
                                        try { stream.remove(id); } catch (Exception ignore) {}
                                        break;
                                }
                            } catch (Exception ex) {
                                log.error("DLQ handler error (plain) for {}", id, ex);
                            }
                            maxId = id;
                        }
                        lastIds.put(s.topic, maxId);
                        continue;
                    }

                    try { stream.createGroup(StreamCreateGroupArgs.name(s.group).id(StreamMessageId.MIN).makeStream()); } catch (Exception ignore) {}
                    Map<StreamMessageId, Map<String, Object>> messages = stream.readGroup(
                            s.group, consumerName, StreamReadGroupArgs.neverDelivered().count(10).timeout(Duration.ofMillis(500))
                    );
                    for (Map.Entry<StreamMessageId, Map<String, Object>> e : messages.entrySet()) {
                        StreamMessageId id = e.getKey();
                        Map<String, Object> data = e.getValue();
                        DeadLetterEntry entry = DeadLetterCodec.parseEntry(id.toString(), data);
                        try {
                            DeadLetterConsumer.HandleResult r = s.handler.handle(entry);
                            switch (r) {
                                case SUCCESS:
                                    stream.ack(s.group, id);
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
                                            // Use client's default codec for replay to be symmetric with test writers
                                            RStream<String, Object> p = redissonClient.getStream("stream:topic:" + topic + ":p:" + pid);
                                            Map<String, Object> d = DeadLetterCodec.buildPartitionEntryFromDlq(data, topic, pid);
                                            p.add(StreamAddArgs.entries(d));
                                            ok = true;
                                        }
                                    } catch (Exception ex) {
                                        log.error("DLQ replay failed", ex);
                                    } finally {
                                        try {
                                            io.github.cuihairu.redis.streaming.reliability.metrics.ReliabilityMetrics.get()
                                                    .recordDlqReplay(entry.getOriginalTopic(), entry.getPartitionId(), ok,
                                                            System.nanoTime() - start);
                                        } catch (Exception ignore) {}
                                        // ack regardless to avoid poison loop; admin can requeue explicitly
                                        stream.ack(s.group, id);
                                    }
                                    break;
                                }
                                case FAIL:
                                    stream.ack(s.group, id);
                                    break;
                            }
                        } catch (Exception ex) {
                            log.error("DLQ handler error for {}", id, ex);
                            stream.ack(s.group, id);
                        }
                    }
                }
            } catch (Exception e) {
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private static class Sub {
        final String topic;
        final String group;
        final DeadLetterHandler handler;
        Sub(String t, String g, DeadLetterHandler h){ this.topic=t; this.group=g; this.handler=h; }
    }
}
