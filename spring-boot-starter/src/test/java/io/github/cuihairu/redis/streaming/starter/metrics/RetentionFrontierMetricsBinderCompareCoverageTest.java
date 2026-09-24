package io.github.cuihairu.redis.streaming.starter.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers RetentionFrontierMetricsBinder computeFrontierAgeMs/compareStreamId/parseMs
 * through the public gauge with crafted stream ids.
 */
class RetentionFrontierMetricsBinderCompareCoverageTest {

    @Test
    void gaugeComputesFrontierAgeAcrossGroups() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of("topicF"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap meta = mock(RMap.class);
        when(redisson.getMap(eq(StreamKeys.topicMeta("topicF")), any(org.redisson.client.codec.Codec.class))).thenReturn(meta);
        when(meta.get("partitionCount")).thenReturn("1");

        long now = System.currentTimeMillis();
        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        Map<String, String> ids = new HashMap<>();
        ids.put("g1", (now - 5_000) + "-9");
        ids.put("g2", (now - 500) + "-1");     // newer -> compare keeps g1 as min
        ids.put("g3", (now - 5_000) + "-3");   // same ms, lower seq -> min
        ids.put("g4", "garbage");              // parseMs -> -1 -> ignored
        ids.put("g5", "");
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicF", 0)))).thenReturn(frontier);
        when(frontier.readAllMap()).thenReturn(ids);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(true);

        MqOptions opts = MqOptions.builder().defaultPartitionCount(1).build();
        RetentionFrontierMetricsBinder binder =
                new RetentionFrontierMetricsBinder(redisson, admin, opts);
        MeterRegistry registry = new SimpleMeterRegistry();
        binder.bindTo(registry);

        Double age = registry.get("redis_streaming_mq_frontier_age_ms").gauge().value();
        assertNotNull(age);
        assertTrue(age >= 4_000 && age < 60_000, "min frontier is g3 at -5s: age=" + age);
    }

    @Test
    void gaugeIsZeroWithoutTopicsOrWithInactiveGroups() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of());

        MqOptions opts = MqOptions.builder().defaultPartitionCount(1).build();
        RetentionFrontierMetricsBinder binder =
                new RetentionFrontierMetricsBinder(redisson, admin, opts);
        MeterRegistry registry = new SimpleMeterRegistry();
        binder.bindTo(registry);
        assertEquals(0.0, registry.get("redis_streaming_mq_frontier_age_ms").gauge().value(), 0.001);
    }

    @Test
    void backendFailuresYieldZeroAge() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenThrow(new IllegalStateException("redis gone"));

        MqOptions opts = MqOptions.builder().defaultPartitionCount(1).build();
        RetentionFrontierMetricsBinder binder =
                new RetentionFrontierMetricsBinder(redisson, admin, opts);
        MeterRegistry registry = new SimpleMeterRegistry();
        binder.bindTo(registry);
        assertEquals(0.0, registry.get("redis_streaming_mq_frontier_age_ms").gauge().value(), 0.001);
    }
}
