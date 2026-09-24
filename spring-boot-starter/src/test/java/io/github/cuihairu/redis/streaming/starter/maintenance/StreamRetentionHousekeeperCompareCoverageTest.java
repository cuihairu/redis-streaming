package io.github.cuihairu.redis.streaming.starter.maintenance;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.HashMap;
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
 * Covers StreamRetentionHousekeeper compareStreamId (via runOnce frontier trimming with
 * crafted stream ids) plus trimTopic/trimDlq retention branches and close().
 */
class StreamRetentionHousekeeperCompareCoverageTest {

    @Test
    void runOnceTrimsFrontierUsingCompareStreamId() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of("topicA"));

        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(List.of());

        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(1L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap meta = mock(RMap.class);
        when(redisson.getMap(eq(StreamKeys.topicMeta("topicA")), any(StringCodec.class))).thenReturn(meta);
        when(meta.get("partitionCount")).thenReturn("1");

        // frontier with three active groups: forces multiple compareStreamId branches
        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        Map<String, String> ids = new HashMap<>();
        ids.put("g1", "100-5");
        ids.put("g2", "100-2");   // same ms, lower seq
        ids.put("g3", "99");      // lower ms, no seq part
        ids.put("g4", "bad-id");  // malformed -> string compare fallback
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicA", 0)), any(StringCodec.class))).thenReturn(frontier);
        when(frontier.readAllMap()).thenReturn(ids);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(true);

        MqOptions opts = MqOptions.builder()
                .defaultPartitionCount(1)
                .retentionMaxLenPerPartition(10)
                .retentionMs(60_000)
                .dlqRetentionMaxLen(5)
                .dlqRetentionMs(30_000)
                .trimIntervalSec(3600)
                .build();
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(redisson, admin, opts);
        try {
            assertDoesNotThrow(keeper::runOnce);
        } finally {
            keeper.close();
        }
    }

    @Test
    void trimDlqDisabledAndInactiveLeasePathsAreTolerated() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of("topicB"));

        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        // discover an unregistered topic via key scan as well
        when(keys.getKeys()).thenReturn(
                List.of(StreamKeys.streamPrefix() + ":topicX:p:0", StreamKeys.streamPrefix() + ":topicX:dlq"));

        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(0L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap meta = mock(RMap.class);
        when(redisson.getMap(anyString(), any(StringCodec.class))).thenReturn(meta);
        when(meta.get("partitionCount")).thenReturn("0");

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        when(frontier.readAllMap()).thenReturn(Map.of("g1", "5-1"));
        // same mock returns meta for topicMeta and frontier for commitFrontier; distinguish via answer
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicX", 0)))).thenReturn(frontier);
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicB", 0)))).thenReturn(frontier);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(false); // inactive groups are skipped

        // dlq retention disabled -> trimDlq returns early
        MqOptions opts = MqOptions.builder()
                .defaultPartitionCount(1)
                .retentionMaxLenPerPartition(0)
                .retentionMs(0)
                .trimIntervalSec(3600)
                .build();
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(redisson, admin, opts);
        try {
            assertDoesNotThrow(keeper::runOnce);
        } finally {
            keeper.close();
        }
    }
}
