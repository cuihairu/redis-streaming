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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamRetentionHousekeeperBehaviorTest {

    @Test
    void runOnceTrimsTopicAndDlqUsingMaxLenMinIdAndFrontier() {
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
        when(redisson.getMap(eq(StreamKeys.topicMeta("topicA")), eq(StringCodec.INSTANCE))).thenReturn(meta);
        when(meta.get("partitionCount")).thenReturn("2");

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicA", 0)))).thenReturn(frontier);
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicA", 1)))).thenReturn(frontier);
        when(frontier.readAllMap()).thenReturn(Map.of("g1", "100-0", "g2", "90-0"));

        @SuppressWarnings({"unchecked", "rawtypes"})
        RBucket bucket = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(bucket);
        when(bucket.isExists()).thenReturn(true);

        MqOptions opts = MqOptions.builder()
                .streamKeyPrefix("stream:topic")
                .defaultPartitionCount(2)
                .retentionMaxLenPerPartition(10)
                .retentionMs(1000)
                .dlqRetentionMaxLen(5)
                .dlqRetentionMs(1000)
                .build();

        // Ensure the housekeeper uses our chosen stream key prefix.
        StreamKeys.configure(opts.getKeyPrefix(), opts.getStreamKeyPrefix());

        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(redisson, admin, opts);
        try {
            assertDoesNotThrow(keeper::runOnce);
        } finally {
            keeper.close();
        }

        verify(script, atLeastOnce()).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER),
                eq(List.of(StreamKeys.dlq("topicA"))), any());
        // At least one trim invocation for partitions should happen.
        verify(script, atLeastOnce()).eval(eq(RScript.Mode.READ_WRITE), anyString(), eq(RScript.ReturnType.INTEGER),
                eq(List.of(StreamKeys.partitionStream("topicA", 0))), any());
    }
}
