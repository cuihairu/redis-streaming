package io.github.cuihairu.redis.streaming.starter.metrics;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Residual coverage for RetentionFrontierMetricsBinder compareStreamId/parseMs branches. */
class RetentionFrontierMetricsBinderResidualCoverageTest {

    private static RetentionFrontierMetricsBinder binder() {
        return new RetentionFrontierMetricsBinder(mock(RedissonClient.class),
                mock(MessageQueueAdmin.class), MqOptions.builder().build());
    }

    private static int compare(RetentionFrontierMetricsBinder b, String a, String bb) throws Exception {
        Method m = RetentionFrontierMetricsBinder.class.getDeclaredMethod("compareStreamId", String.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(b, a, bb);
    }

    private static long parseMs(RetentionFrontierMetricsBinder b, String id) throws Exception {
        Method m = RetentionFrontierMetricsBinder.class.getDeclaredMethod("parseMs", String.class);
        m.setAccessible(true);
        return (long) m.invoke(b, id);
    }

    @Test
    void compareStreamIdAndParseMsBranchMatrix() throws Exception {
        RetentionFrontierMetricsBinder b = binder();
        assertEquals(-1, compare(b, "5-1", "6-0"));
        assertEquals(1, compare(b, "6-0", "5-9"));
        assertEquals(-1, compare(b, "5-1", "5-2"));
        assertEquals(1, compare(b, "5-2", "5-1"));
        assertEquals(-1, compare(b, "5", "5-1"));
        assertEquals(1, compare(b, "5-1", "5"));
        assertEquals(0, compare(b, "5", "5"));
        assertEquals(0, compare(b, "5-1", "5-1"));
        assertEquals("a".compareTo("b"), compare(b, "a", "b"));

        assertEquals(123L, parseMs(b, "123-4"));
        assertEquals(7L, parseMs(b, "7"));
        assertEquals(-1L, parseMs(b, "garbage"));
    }

    @Test
    void computeFrontierAgeWithZeroPartitionCountFallsBack() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(java.util.List.of("t"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap meta = mock(RMap.class);
        when(meta.get("partitionCount")).thenReturn("0"); // registry coerces to 1
        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        when(frontier.readAllMap()).thenReturn(Map.of("g1", "not-a-stream-id"));
        when(redisson.getMap(eq(StreamKeys.topicMeta("t")), any(org.redisson.client.codec.Codec.class))).thenReturn(meta);
        when(redisson.getMap(eq(StreamKeys.commitFrontier("t", 0)))).thenReturn(frontier);
        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(true);

        RetentionFrontierMetricsBinder binder = new RetentionFrontierMetricsBinder(redisson, admin,
                MqOptions.builder().defaultPartitionCount(1).build());
        MeterRegistry registry = new SimpleMeterRegistry();
        binder.bindTo(registry);
        Double age = registry.get("redis_streaming_mq_frontier_age_ms").gauge().value();
        assertNotNull(age);
        assertEquals(0.0, age, 0.001, "malformed frontier id yields zero age");
    }
}
