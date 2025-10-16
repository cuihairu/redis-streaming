package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;

import java.util.List;

/**
 * Gauge for retention frontier age (ms):
 * - For each topic/partition, compute min committed stream id across active groups (lease exists);
 * - Decode id to epoch millis; age = now - minMillis;
 * - Report the maximum age across all partitions as a single gauge.
 *
 * Note: This is a coarse gauge for observability; avoid heavy per-topic/per-partition gauges.
 */
public class RetentionFrontierMetricsBinder implements io.micrometer.core.instrument.binder.MeterBinder {

    private final RedissonClient redissonClient;
    private final MessageQueueAdmin admin;
    private final MqOptions options;

    public RetentionFrontierMetricsBinder(RedissonClient redissonClient, MessageQueueAdmin admin, MqOptions options) {
        this.redissonClient = redissonClient;
        this.admin = admin;
        this.options = options;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("redis_streaming_mq_frontier_age_ms", this::computeFrontierAgeMs)
                .description("Max frontier age across all partitions (ms)")
                .register(registry);
    }

    private double computeFrontierAgeMs() {
        long now = System.currentTimeMillis();
        long maxAge = 0L;
        try {
            List<String> topics = admin.listAllTopics();
            for (String topic : topics) {
                int pc = new io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry(redissonClient).getPartitionCount(topic);
                if (pc <= 0) pc = options.getDefaultPartitionCount();
                if (pc <= 0) pc = 1;
                for (int i = 0; i < pc; i++) {
                    String frontierKey = StreamKeys.commitFrontier(topic, i);
                    org.redisson.api.RMap<String,String> fmap = redissonClient.getMap(frontierKey);
                    java.util.Map<String,String> fm = fmap.readAllMap();
                    if (fm == null || fm.isEmpty()) continue;
                    String minId = null;
                    for (var e : fm.entrySet()) {
                        String group = e.getKey();
                        // active if lease exists for this partition
                        String leaseKey = StreamKeys.lease(topic, group, i);
                        boolean live = redissonClient.getBucket(leaseKey).isExists();
                        if (!live) continue;
                        String id = e.getValue();
                        if (id == null || id.isEmpty()) continue;
                        if (minId == null) minId = id; else if (compareStreamId(id, minId) < 0) minId = id;
                    }
                    if (minId != null) {
                        long ms = parseMs(minId);
                        if (ms > 0) {
                            long age = now - ms;
                            if (age > maxAge) maxAge = age;
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        return (double) Math.max(0L, maxAge);
    }

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

    private long parseMs(String id) {
        try {
            String[] p = id.split("-", 2);
            return Long.parseLong(p[0]);
        } catch (Exception e) { return -1L; }
    }
}
