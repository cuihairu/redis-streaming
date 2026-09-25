package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the remaining {@code computeFrontierAgeMs()} branches: null/empty frontier maps,
 * null/empty frontier values and the max-age comparison across partitions.
 */
@Timeout(30)
class RetentionFrontierMetricsBinderResidualCoverage2Test {

    @Test
    void gaugeTracksOldestActiveFrontierAcrossPartitions() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of("t"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap meta = mock(RMap.class);
        when(meta.get("partitionCount")).thenReturn("5");
        when(redisson.getMap(eq(StreamKeys.topicMeta("t")), any(org.redisson.client.codec.Codec.class)))
                .thenReturn(meta);

        long now = System.currentTimeMillis();
        stubPartition(redisson, "t", 0, null);
        stubPartition(redisson, "t", 1, Map.of());
        stubPartition(redisson, "t", 2, Map.of("g", (now - 100) + "-0"));
        stubPartition(redisson, "t", 3, Map.of("g", (now - 50) + "-0"));

        Map<String, String> mixed = new HashMap<>();
        mixed.put("gNull", null);
        mixed.put("gEmpty", "");
        mixed.put("gLive", (now - 300) + "-0");
        stubPartition(redisson, "t", 4, mixed);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(true);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new RetentionFrontierMetricsBinder(redisson, admin,
                MqOptions.builder().defaultPartitionCount(1).build()).bindTo(registry);

        double age = registry.get("redis_streaming_mq_frontier_age_ms").gauge().value();
        assertTrue(age >= 250 && age <= 3000,
                "gauge should report the oldest active frontier (~300ms), got " + age);
    }

    @Test
    void gaugeIsZeroWhenNothingEligible() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of("t"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap meta = mock(RMap.class);
        when(meta.get("partitionCount")).thenReturn("2");
        when(redisson.getMap(eq(StreamKeys.topicMeta("t")), any(org.redisson.client.codec.Codec.class)))
                .thenReturn(meta);

        stubPartition(redisson, "t", 0, null);
        stubPartition(redisson, "t", 1, Map.of("g", ""));

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(false);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new RetentionFrontierMetricsBinder(redisson, admin,
                MqOptions.builder().defaultPartitionCount(1).build()).bindTo(registry);

        assertEquals(0.0, registry.get("redis_streaming_mq_frontier_age_ms").gauge().value());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stubPartition(RedissonClient redisson, String topic, int partition,
                                      Map<String, String> frontier) {
        RMap fmap = mock(RMap.class);
        when(fmap.readAllMap()).thenReturn(frontier);
        when(redisson.getMap(eq(StreamKeys.commitFrontier(topic, partition)))).thenReturn(fmap);
    }
}
