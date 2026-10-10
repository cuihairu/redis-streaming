package io.github.cuihairu.redis.streaming.starter.maintenance;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
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

    /** A topic discovered under a tenant namespace ("default" = pre-tenant layout). */
    private record TenantTopic(String tenant, String topic) {}

    /** Tenants with a configured retention cap (never contains the default tenant). */
    private java.util.Set<String> cappedTenants() {
        java.util.Set<String> out = new java.util.TreeSet<>(options.getTenantRetentionMaxLenPerPartition().keySet());
        out.remove(io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.DEFAULT_TENANT);
        return out;
    }

    /** Retention cap for a discovered tenant: per-tenant override, else the global cap. */
    private long maxLenFor(String tenant) {
        Integer override = options.getTenantRetentionMaxLenPerPartition().get(tenant);
        return override != null ? Math.max(0, override) : Math.max(0, options.getRetentionMaxLenPerPartition());
    }

    private StreamKeys viewFor(String tenant) {
        return new StreamKeys(options.getKeyPrefix(), options.getStreamKeyPrefix(), tenant);
    }

    public void runOnce() {
        try {
            String ownTenant = StreamKeys.of(options).getTenant();
            java.util.Set<TenantTopic> topics = new java.util.LinkedHashSet<>();
            for (String t : admin.listAllTopics()) {
                topics.add(new TenantTopic(ownTenant, t));
            }
            // Discover topics by scanning keys for partition streams and DLQ keys (handles
            // unregistered topics). A key whose first segment names a tenant with a configured
            // cap belongs to that tenant; every other key parses under the pre-tenant layout.
            java.util.Set<String> tenants = cappedTenants();
            try {
                String sp = options.getStreamKeyPrefix();
                String start = sp + ":"; // e.g., "stream:topic:"
                for (String key : redissonClient.getKeys().getKeys()) {
                    if (key == null || !key.startsWith(start)) continue;
                    String rest = key.substring(start.length());
                    String tenant = io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.DEFAULT_TENANT;
                    int sep = rest.indexOf(':');
                    if (sep > 0 && tenants.contains(rest.substring(0, sep))) {
                        tenant = rest.substring(0, sep);
                        rest = rest.substring(sep + 1);
                    }
                    if (rest.endsWith(":dlq")) {
                        String t = rest.substring(0, rest.length() - 4);
                        if (!t.isEmpty()) topics.add(new TenantTopic(tenant, t));
                    } else {
                        int idx = rest.indexOf(":p:");
                        if (idx > 0) {
                            String t = rest.substring(0, idx);
                            if (!t.isEmpty()) topics.add(new TenantTopic(tenant, t));
                        }
                    }
                }
            } catch (Exception ignore) {}
            for (TenantTopic tt : topics) {
                try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordTrim(tt.topic(), -1, 0L, "housekeeper"); } catch (Exception ignore) {}
                trimTopic(tt.tenant(), tt.topic());
                trimDlq(tt.tenant(), tt.topic());
            }
        } catch (Exception e) {
            log.warn("Retention housekeeper iteration failed", e);
        }
    }

    private void trimTopic(String tenant, String topic) {
        try {
            StreamKeys keys = viewFor(tenant);
            int pc = new TopicPartitionRegistry(redissonClient, keys).getPartitionCount(topic);
            if (pc <= 0) pc = options.getDefaultPartitionCount();
            if (pc <= 0) pc = 1;

            long maxLen = maxLenFor(tenant);
            long retentionMs = Math.max(0, options.getRetentionMs());

            for (int i = 0; i < pc; i++) {
                String streamKey = keys.partitionStreamKey(topic, i);
                try {
                    if (maxLen > 0) {
                        // Use precise MAXLEN for deterministic bounds; write-path also trims precisely
                        String lua = "return redis.call('XTRIM', KEYS[1], 'MAXLEN', ARGV[1])";
                        Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.LONG,
                                java.util.Collections.singletonList(streamKey), String.valueOf(maxLen));
                        try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordTrim(topic, i, deleted != null ? deleted : 0L, "maxlen"); } catch (Exception ignore) {}
                    }
                    if (retentionMs > 0) {
                        long minTs = System.currentTimeMillis() - retentionMs;
                        String minId = Long.toString(minTs) + "-0";
                        // XTRIM stream MINID ~ minId (approximate is acceptable for time-based)
                        String lua2 = "return redis.call('XTRIM', KEYS[1], 'MINID', '~', ARGV[1])";
                        Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua2, RScript.ReturnType.LONG,
                                java.util.Collections.singletonList(streamKey), minId);
                        try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordTrim(topic, i, deleted != null ? deleted : 0L, "minid"); } catch (Exception ignore) {}
                    }
                    // Safe frontier trim: compute min committed id across active groups for this partition
                    String frontierKey = keys.commitFrontierKey(topic, i);
                    org.redisson.api.RMap<String, String> fm = redissonClient.getMap(frontierKey);
                    java.util.Map<String,String> all = fm.readAllMap();
                    if (all != null && !all.isEmpty()) {
                        String minId = null;
                        for (java.util.Map.Entry<String,String> e : all.entrySet()) {
                            String group = e.getKey();
                            // consider group active if lease exists for this partition
                            String leaseKey = keys.leaseKey(topic, group, i);
                            boolean active = redissonClient.getBucket(leaseKey).isExists();
                            if (!active) continue;
                            String val = e.getValue();
                            if (val == null || val.isEmpty()) continue;
                            if (minId == null) minId = val; else if (compareStreamId(val, minId) < 0) minId = val;
                        }
                        if (minId != null) {
                            String lua3 = "return redis.call('XTRIM', KEYS[1], 'MINID', '~', ARGV[1])";
                            Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua3, RScript.ReturnType.LONG,
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

    private void trimDlq(String tenant, String topic) {
        try {
            int maxLen = Math.max(0, options.getDlqRetentionMaxLen());
            long retentionMs = Math.max(0, options.getDlqRetentionMs());
            if (maxLen <= 0 && retentionMs <= 0) return; // disabled
            String dlqKey = viewFor(tenant).dlqKey(topic);
            if (maxLen > 0) {
                String lua = "return redis.call('XTRIM', KEYS[1], 'MAXLEN', ARGV[1])";
                Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua, RScript.ReturnType.LONG,
                        java.util.Collections.singletonList(dlqKey), String.valueOf(maxLen));
                try { io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics.get().recordDlqTrim(topic, deleted != null ? deleted : 0L, "maxlen"); } catch (Exception ignore) {}
            }
            if (retentionMs > 0) {
                long minTs = System.currentTimeMillis() - retentionMs;
                String minId = Long.toString(minTs) + "-0";
                String lua2 = "return redis.call('XTRIM', KEYS[1], 'MINID', '~', ARGV[1])";
                Long deleted = redissonClient.getScript().eval(RScript.Mode.READ_WRITE, lua2, RScript.ReturnType.LONG,
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
