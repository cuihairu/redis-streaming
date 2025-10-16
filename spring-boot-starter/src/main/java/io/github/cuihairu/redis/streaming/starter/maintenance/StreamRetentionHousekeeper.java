package io.github.cuihairu.redis.streaming.starter.maintenance;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background retention housekeeper for Redis Streams.
 *
 * Strategy (low-overhead defaults):
 * - Length-based retention: XTRIM MAXLEN ~ retentionMaxLenPerPartition for each partition stream;
 * - Optional age-based retention (disabled by default): XTRIM MINID ~ (now - retentionMs)-0;
 *
 * This runs at a fixed cadence (trimIntervalSec). Work per run is small (XTRIM is efficient),
 * and we avoid scanning/removing entries one by one.
 */
@Slf4j
public class StreamRetentionHousekeeper implements AutoCloseable {

    private final RedissonClient redissonClient;
    private final MessageQueueAdmin admin;
    private final MqOptions options;
    private final ScheduledExecutorService exec;

    public StreamRetentionHousekeeper(RedissonClient redissonClient, MessageQueueAdmin admin, MqOptions options) {
        this.redissonClient = Objects.requireNonNull(redissonClient);
        this.admin = Objects.requireNonNull(admin);
        this.options = Objects.requireNonNull(options);
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rs-retention");
            t.setDaemon(true);
            return t;
        });
        long initialDelay = 0; // start immediately to reduce latency/flakiness in tests and small deployments
        this.exec.scheduleWithFixedDelay(this::runOnce, initialDelay, options.getTrimIntervalSec(), TimeUnit.SECONDS);
        log.info("StreamRetentionHousekeeper started (interval={}s, maxLenPerPartition={}, retentionMs={})",
                options.getTrimIntervalSec(), options.getRetentionMaxLenPerPartition(), options.getRetentionMs());
    }

    public void runOnce() {
        try {
            java.util.Set<String> topics = new java.util.LinkedHashSet<>(admin.listAllTopics());
            // Discover topics by scanning keys for partition streams and DLQ keys (handles unregistered topics)
            try {
                String sp = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.streamPrefix();
                String start = sp + ":"; // e.g., "stream:topic:"
                for (String key : redissonClient.getKeys().getKeys()) {
                    if (key == null) continue;
                    if (key.startsWith(start)) {
                        if (key.endsWith(":dlq")) {
                            String t = key.substring(start.length(), key.length() - 4);
                            if (!t.isEmpty()) topics.add(t);
                        } else {
                            int idx = key.indexOf(":p:");
                            if (idx > start.length()) {
                                String t = key.substring(start.length(), idx);
                                if (!t.isEmpty()) topics.add(t);
                            }
                        }
                    }
                }
            } catch (Exception ignore) {}
            for (String topic : topics) {
                try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordTrim(topic, -1, 0L, "housekeeper"); } catch (Exception ignore) {}
                trimTopic(topic);
                trimDlq(topic);
            }
        } catch (Exception e) {
            log.warn("Retention housekeeper iteration failed", e);
        }
    }

    private void trimTopic(String topic) {
        try {
            int pc = new TopicPartitionRegistry(redissonClient).getPartitionCount(topic);
            if (pc <= 0) pc = options.getDefaultPartitionCount();
            if (pc <= 0) pc = 1;

            long maxLen = Math.max(0, options.getRetentionMaxLenPerPartition());
            long retentionMs = Math.max(0, options.getRetentionMs());

            for (int i = 0; i < pc; i++) {
                String streamKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.partitionStream(topic, i);
                try {
                    if (maxLen > 0) {
                        // Use precise MAXLEN for deterministic bounds; write-path also trims precisely
                        String lua = "return redis.call('XTRIM', KEYS[1], 'MAXLEN', ARGV[1])";
                        Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER,
                                java.util.Collections.singletonList(streamKey), String.valueOf(maxLen));
                        try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordTrim(topic, i, deleted != null ? deleted : 0L, "maxlen"); } catch (Exception ignore) {}
                    }
                    if (retentionMs > 0) {
                        long minTs = System.currentTimeMillis() - retentionMs;
                        String minId = Long.toString(minTs) + "-0";
                        // XTRIM stream MINID ~ minId (approximate is acceptable for time-based)
                        String lua2 = "return redis.call('XTRIM', KEYS[1], 'MINID', '~', ARGV[1])";
                        Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua2, RScript.ReturnType.INTEGER,
                                java.util.Collections.singletonList(streamKey), minId);
                        try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordTrim(topic, i, deleted != null ? deleted : 0L, "minid"); } catch (Exception ignore) {}
                    }
                    // Safe frontier trim: compute min committed id across active groups for this partition
                    String frontierKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.commitFrontier(topic, i);
                    org.redisson.api.RMap<String, String> fm = redissonClient.getMap(frontierKey);
                    java.util.Map<String,String> all = fm.readAllMap();
                    if (all != null && !all.isEmpty()) {
                        String minId = null;
                        for (java.util.Map.Entry<String,String> e : all.entrySet()) {
                            String group = e.getKey();
                            // consider group active if lease exists for this partition
                            String leaseKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.lease(topic, group, i);
                            boolean active = redissonClient.getBucket(leaseKey).isExists();
                            if (!active) continue;
                            String val = e.getValue();
                            if (val == null || val.isEmpty()) continue;
                            if (minId == null) minId = val; else if (compareStreamId(val, minId) < 0) minId = val;
                        }
                        if (minId != null) {
                            String lua3 = "return redis.call('XTRIM', KEYS[1], 'MINID', '~', ARGV[1])";
                            Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua3, RScript.ReturnType.INTEGER,
                                    java.util.Collections.singletonList(streamKey), minId);
                            try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordTrim(topic, i, deleted != null ? deleted : 0L, "frontier"); } catch (Exception ignore) {}
                        }
                    }
                } catch (Exception ex) {
                    // best-effort per partition
                    log.debug("Retention trim failed for {}:p{}", topic, i, ex);
                }
            }
        } catch (Exception e) {
            log.debug("Retention trim failed for topic {}", topic, e);
        }
    }

    private void trimDlq(String topic) {
        try {
            int maxLen = Math.max(0, options.getDlqRetentionMaxLen());
            long retentionMs = Math.max(0, options.getDlqRetentionMs());
            if (maxLen <= 0 && retentionMs <= 0) return; // disabled
            String dlqKey = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.dlq(topic);
            if (maxLen > 0) {
                String lua = "return redis.call('XTRIM', KEYS[1], 'MAXLEN', ARGV[1])";
                Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.INTEGER,
                        java.util.Collections.singletonList(dlqKey), String.valueOf(maxLen));
                try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordDlqTrim(topic, deleted != null ? deleted : 0L, "maxlen"); } catch (Exception ignore) {}
            }
            if (retentionMs > 0) {
                long minTs = System.currentTimeMillis() - retentionMs;
                String minId = Long.toString(minTs) + "-0";
                String lua2 = "return redis.call('XTRIM', KEYS[1], 'MINID', '~', ARGV[1])";
                Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua2, RScript.ReturnType.INTEGER,
                        java.util.Collections.singletonList(dlqKey), minId);
                try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordDlqTrim(topic, deleted != null ? deleted : 0L, "minid"); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            log.debug("DLQ retention trim failed for topic {}", topic, e);
        }
    }

    // Compare Redis Stream ID strings like "ms-seq"
    private int compareStreamId(String a, String b) {
        try {
            String[] pa = a.split("-", 2); String[] pb = b.split("-", 2);
            long am = Long.parseLong(pa[0]); long bm = Long.parseLong(pb[0]);
            if (am != bm) return am < bm ? -1 : 1;
            long as = pa.length>1?Long.parseLong(pa[1]):0L; long bs = pb.length>1?Long.parseLong(pb[1]):0L;
            if (as != bs) return as < bs ? -1 : 1;
            return 0;
        } catch (Exception e) { return a.compareTo(b); }
    }

    @Override
    public void close() {
        exec.shutdown();
        try { exec.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
