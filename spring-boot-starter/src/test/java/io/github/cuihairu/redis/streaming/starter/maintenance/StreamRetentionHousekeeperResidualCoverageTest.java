package io.github.cuihairu.redis.streaming.starter.maintenance;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetrics;
import io.github.cuihairu.redis.streaming.mq.metrics.RetentionMetricsCollector;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for StreamRetentionHousekeeper: RetentionMetrics recording failures are
 * swallowed at every call site, and a hostile options mock triggers the outer trimTopic catch.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class StreamRetentionHousekeeperResidualCoverageTest {

    private static final RetentionMetricsCollector NOOP = new RetentionMetricsCollector() {
        @Override public void recordTrim(String topic, int partitionId, long deleted, String reason) {}
        @Override public void recordDlqTrim(String topic, long deleted, String reason) {}
    };

    private static final RetentionMetricsCollector THROWING = new RetentionMetricsCollector() {
        @Override public void recordTrim(String topic, int partitionId, long deleted, String reason) {
            throw new IllegalStateException("trim metric boom");
        }
        @Override public void recordDlqTrim(String topic, long deleted, String reason) {
            throw new IllegalStateException("dlq metric boom");
        }
    };

    @BeforeEach
    void installNoopBaseline() {
        RetentionMetrics.setCollector(NOOP);
    }

    @AfterEach
    void restoreNoop() {
        RetentionMetrics.setCollector(NOOP);
    }

    private void stubTopic(RedissonClient redisson, String topic, int partitions) {
        @SuppressWarnings("rawtypes")
        RMap meta = mock(RMap.class);
        when(meta.get("partitionCount")).thenReturn(partitions > 1 ? String.valueOf(partitions) : null);
        when(redisson.getMap(eq(StreamKeys.topicMeta(topic)), any(org.redisson.client.codec.Codec.class))).thenReturn(meta);

        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(2L);

        @SuppressWarnings("rawtypes")
        RMap frontier = mock(RMap.class);
        when(frontier.readAllMap()).thenReturn(Map.of("g1", "10-5"));
        for (int i = 0; i < Math.max(1, partitions); i++) {
            when(redisson.getMap(eq(StreamKeys.commitFrontier(topic, i)))).thenReturn(frontier);
        }

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(true);
    }

    @Test
    void retentionMetricRecordingFailuresAreSwallowedEverywhere() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of("tmetric"));
        org.redisson.api.RKeys keys = org.mockito.Mockito.mock(org.redisson.api.RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(List.of());
        stubTopic(redisson, "tmetric", 1);

        MqOptions opts = MqOptions.builder()
                .retentionMaxLenPerPartition(2)
                .retentionMs(1_000)
                .dlqRetentionMaxLen(1)
                .dlqRetentionMs(1_000)
                .trimIntervalSec(3600)
                .build();
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(redisson, admin, opts);
        RetentionMetrics.setCollector(THROWING);
        try {
            assertDoesNotThrow(keeper::runOnce);
        } finally {
            keeper.close();
        }
    }

    @Test
    void trimTopicOuterCatchHandlesHostileOptions() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of());
        org.redisson.api.RKeys keys = org.mockito.Mockito.mock(org.redisson.api.RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(List.of());
        stubTopic(redisson, "thostile", 1);

        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(redisson, admin,
                MqOptions.builder().trimIntervalSec(3600).build());
        try {
            MqOptions hostile = mock(MqOptions.class);
            when(hostile.getRetentionMaxLenPerPartition()).thenThrow(new IllegalStateException("options boom"));
            Field f = StreamRetentionHousekeeper.class.getDeclaredField("options");
            f.setAccessible(true);
            f.set(keeper, hostile);

            Method trimTopic = StreamRetentionHousekeeper.class.getDeclaredMethod("trimTopic", String.class);
            trimTopic.setAccessible(true);
            assertDoesNotThrow(() -> trimTopic.invoke(keeper, "thostile"));
        } finally {
            keeper.close();
        }
    }
}
