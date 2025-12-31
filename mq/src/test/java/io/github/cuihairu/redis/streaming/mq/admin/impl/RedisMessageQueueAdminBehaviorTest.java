package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.admin.model.ConsumerGroupInfo;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.PendingEntry;
import org.redisson.api.RKeys;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamGroup;
import org.redisson.api.StreamMessageId;
import org.redisson.api.StreamInfo;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisMessageQueueAdminBehaviorTest {

    @Test
    void topicExistsChecksAllPartitions() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s0 = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s1 = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s2 = mock(RStream.class);

        when(partitionRegistry.getPartitionCount("t")).thenReturn(3);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s0);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 1)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s1);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 2)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s2);

        when(s0.isExists()).thenReturn(false);
        when(s1.isExists()).thenReturn(false);
        when(s2.isExists()).thenReturn(true);

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        assertTrue(admin.topicExists("t"));
    }

    @Test
    void getQueueInfoSinglePartitionFallsBackToLegacyTopicKey() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> partitionStream = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> legacyStream = mock(RStream.class);
        @SuppressWarnings("unchecked")
        StreamInfo<String, Object> info = mock(StreamInfo.class);
        @SuppressWarnings("unchecked")
        StreamInfo.Entry<String, Object> first = mock(StreamInfo.Entry.class);

        when(partitionRegistry.getPartitionCount("topic")).thenReturn(1);
        when(redisson.getStream(eq(StreamKeys.partitionStream("topic", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) partitionStream);
        when(redisson.getStream(eq("topic"), eq(StringCodec.INSTANCE))).thenReturn((RStream) legacyStream);

        when(partitionStream.isExists()).thenReturn(false);
        when(legacyStream.isExists()).thenReturn(true);
        when(legacyStream.size()).thenReturn(7L);
        when(legacyStream.getInfo()).thenReturn(info);
        when(info.getGroups()).thenReturn(2);
        when(info.getFirstEntry()).thenReturn(first);
        when(first.getId()).thenReturn(new StreamMessageId(1, 0));
        when(info.getLastGeneratedId()).thenReturn(new StreamMessageId(123, 0));

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        QueueInfo qi = admin.getQueueInfo("topic");

        assertNotNull(qi);
        assertTrue(qi.isExists());
        assertEquals(7, qi.getLength());
        assertEquals(2, qi.getConsumerGroupCount());
        assertNotNull(qi.getFirstMessageId());
        assertNotNull(qi.getLastMessageId());
    }

    @Test
    void getConsumerGroupsAggregatesAcrossPartitions() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s0 = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s1 = mock(RStream.class);

        StreamGroup g0 = mock(StreamGroup.class);
        StreamGroup g1 = mock(StreamGroup.class);
        when(g0.getName()).thenReturn("g");
        when(g0.getConsumers()).thenReturn(1);
        when(g0.getPending()).thenReturn(2);
        when(g0.getLastDeliveredId()).thenReturn(new StreamMessageId(10, 0));
        when(g1.getName()).thenReturn("g");
        when(g1.getConsumers()).thenReturn(3);
        when(g1.getPending()).thenReturn(4);
        when(g1.getLastDeliveredId()).thenReturn(new StreamMessageId(11, 0));

        when(partitionRegistry.getPartitionCount("t")).thenReturn(2);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s0);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 1)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s1);
        when(s0.isExists()).thenReturn(true);
        when(s1.isExists()).thenReturn(true);
        when(s0.listGroups()).thenReturn(List.of(g0));
        when(s1.listGroups()).thenReturn(List.of(g1));

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        List<ConsumerGroupInfo> groups = admin.getConsumerGroups("t");

        assertEquals(1, groups.size());
        ConsumerGroupInfo g = groups.get(0);
        assertEquals("g", g.getName());
        assertEquals(4, g.getConsumers());
        assertEquals(6L, g.getPending());
        assertNotNull(g.getLastDeliveredId());
    }

    @Test
    void getPendingMessagesReadsAndReturnsResults() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> stream = mock(RStream.class);
        PendingEntry p0 = mock(PendingEntry.class);
        PendingEntry p1 = mock(PendingEntry.class);

        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) stream);
        when(stream.isExists()).thenReturn(true);

        when(p0.getIdleTime()).thenReturn(5000L);
        when(p0.getLastTimeDelivered()).thenReturn(1L);
        when(p0.getId()).thenReturn(new StreamMessageId(1, 0));
        when(p0.getConsumerName()).thenReturn("c1");

        when(p1.getIdleTime()).thenReturn(1000L);
        when(p1.getLastTimeDelivered()).thenReturn(2L);
        when(p1.getId()).thenReturn(new StreamMessageId(2, 0));
        when(p1.getConsumerName()).thenReturn("c2");

        @SuppressWarnings("deprecation")
        List<PendingEntry> pendingEntries = List.of(p0, p1);
        @SuppressWarnings("deprecation")
        List<PendingEntry> toReturn = pendingEntries;
        when(stream.listPending(eq("g"), eq(StreamMessageId.MIN), eq(StreamMessageId.MAX), anyInt())).thenReturn(toReturn);

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        var out = admin.getPendingMessages("t", "g", 10);

        assertEquals(2, out.size());
        assertEquals("c1", out.get(0).getConsumerName());
        assertEquals("c2", out.get(1).getConsumerName());
        assertFalse(out.get(0).getIdleTime().isNegative());
    }

    @Test
    void rangeReverseParsesLuaResult() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> stream = mock(RStream.class);
        RScript script = mock(RScript.class);

        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) stream);
        when(stream.isExists()).thenReturn(true);
        when(redisson.getScript(eq(StringCodec.INSTANCE))).thenReturn(script);

        List<Object> rows = List.of(
                List.of("2-0", List.of("k1", "v1", "k2", "v2")),
                List.of("1-0", List.of("x", "y"))
        );
        when(script.eval(
                eq(RScript.Mode.READ_ONLY),
                any(String.class),
                eq(RScript.ReturnType.MULTI),
                any(List.class),
                any(),
                any(),
                any()
        )).thenReturn(rows);

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        var out = admin.range("t", 0, null, null, 10, true);

        assertEquals(2, out.size());
        assertEquals("2-0", out.get(0).getId());
        assertEquals(0, out.get(0).getPartitionId());
        assertEquals(Map.of("k1", "v1", "k2", "v2"), out.get(0).getFields());
    }

    @Test
    void trimQueueDeletesOldEntriesPerPartition() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s0 = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s1 = mock(RStream.class);

        when(partitionRegistry.getPartitionCount("t")).thenReturn(2);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s0);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 1)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s1);

        when(s0.isExists()).thenReturn(true);
        when(s1.isExists()).thenReturn(true);
        when(s0.size()).thenReturn(7L);
        when(s1.size()).thenReturn(3L);

        Map<StreamMessageId, Map<String, Object>> old = new java.util.LinkedHashMap<>();
        old.put(new StreamMessageId(1, 0), Map.of("a", "1"));
        old.put(new StreamMessageId(2, 0), Map.of("a", "2"));
        @SuppressWarnings("deprecation")
        Map<StreamMessageId, Map<String, Object>> toReturn = old;
        when(s0.range(anyInt(), eq(StreamMessageId.MIN), eq(StreamMessageId.MAX))).thenReturn(toReturn);

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        long deleted = admin.trimQueue("t", 10);

        assertEquals(2L, deleted);
        for (StreamMessageId id : old.keySet()) {
            verify(s0).remove(id);
        }
    }

    @Test
    void deleteTopicDeletesKeysAndPayloadHashes() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);
        RKeys keys = mock(RKeys.class);

        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.delete(anyString())).thenReturn(1L);
        when(payloadLifecycleManager.cleanupTopicPayloadHashes("t")).thenReturn(3L);

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        assertTrue(admin.deleteTopic("t"));
    }

    @Test
    void getPendingCountSumsAcrossPartitions() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s0 = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s1 = mock(RStream.class);
        StreamGroup g0 = mock(StreamGroup.class);
        StreamGroup g1 = mock(StreamGroup.class);

        when(partitionRegistry.getPartitionCount("t")).thenReturn(2);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s0);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 1)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s1);

        when(s0.isExists()).thenReturn(true);
        when(s1.isExists()).thenReturn(true);
        when(g0.getName()).thenReturn("g");
        when(g0.getPending()).thenReturn(2);
        when(g1.getName()).thenReturn("g");
        when(g1.getPending()).thenReturn(3);
        when(s0.listGroups()).thenReturn(List.of(g0));
        when(s1.listGroups()).thenReturn(List.of(g1));

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        assertEquals(5L, admin.getPendingCount("t", "g"));
    }

    @Test
    void trimQueueByAgeRemovesIds() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s0 = mock(RStream.class);
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s0);
        when(s0.isExists()).thenReturn(true);

        Map<StreamMessageId, Map<String, Object>> old = new java.util.LinkedHashMap<>();
        old.put(new StreamMessageId(1, 0), Map.of("a", "1"));
        old.put(new StreamMessageId(2, 0), Map.of("a", "2"));
        @SuppressWarnings("deprecation")
        Map<StreamMessageId, Map<String, Object>> toReturn = old;
        when(s0.range(anyInt(), eq(StreamMessageId.MIN), any(StreamMessageId.class))).thenReturn(toReturn);

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        long deleted = admin.trimQueueByAge("t", Duration.ofMinutes(5));

        assertEquals(2L, deleted);
        verify(s0).remove(new StreamMessageId(1, 0));
        verify(s0).remove(new StreamMessageId(2, 0));
    }

    @Test
    void deleteConsumerGroupRemovesAcrossKnownAndScannedKeys() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);
        RKeys keys = mock(RKeys.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s0 = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> sExtra = mock(RStream.class);

        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(List.of(StreamKeys.partitionStream("t", 0), StreamKeys.partitionStream("t", 9)));
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s0);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 9)), eq(StringCodec.INSTANCE))).thenReturn((RStream) sExtra);

        when(s0.isExists()).thenReturn(true);
        when(sExtra.isExists()).thenReturn(true);

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        assertTrue(admin.deleteConsumerGroup("t", "g"));
        verify(s0).removeGroup("g");
        verify(sExtra).removeGroup("g");
    }

    @Test
    void resetConsumerGroupOffsetCreatesGroupAndHandlesBusyGroup() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s0 = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s1 = mock(RStream.class);

        when(partitionRegistry.getPartitionCount("t")).thenReturn(2);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s0);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 1)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s1);

        org.mockito.Mockito.doThrow(new RuntimeException("BUSYGROUP Consumer Group name already exists"))
                .when(s1).createGroup(any(StreamCreateGroupArgs.class));

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        assertTrue(admin.resetConsumerGroupOffset("t", "g", "0"));
        verify(s0).createGroup(any(StreamCreateGroupArgs.class));
        verify(s1).createGroup(any(StreamCreateGroupArgs.class));
    }

    @Test
    void updatePartitionCountDelegatesToRegistry() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        when(partitionRegistry.updatePartitionCount("t", 3)).thenReturn(true);

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        assertTrue(admin.updatePartitionCount("t", 3));
    }

    @Test
    void listRecentParsesRowsAndSortsByTimestampDesc() {
        RedissonClient redisson = mock(RedissonClient.class);
        TopicRegistry topicRegistry = mock(TopicRegistry.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        PayloadLifecycleManager payloadLifecycleManager = mock(PayloadLifecycleManager.class);

        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s0 = mock(RStream.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RStream<String, Object> s1 = mock(RStream.class);
        RScript script = mock(RScript.class);

        when(partitionRegistry.getPartitionCount("t")).thenReturn(2);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s0);
        when(redisson.getStream(eq(StreamKeys.partitionStream("t", 1)), eq(StringCodec.INSTANCE))).thenReturn((RStream) s1);
        when(s0.isExists()).thenReturn(true);
        when(s1.isExists()).thenReturn(true);
        when(redisson.getScript(eq(StringCodec.INSTANCE))).thenReturn(script);

        List<Object> rows0 = List.of(List.of("2-0", List.of("k", "v")));
        List<Object> rows1 = List.of(List.of("5-0", List.of("x", "y")));
        when(script.eval(
                eq(RScript.Mode.READ_ONLY),
                any(String.class),
                eq(RScript.ReturnType.MULTI),
                any(List.class),
                any(),
                any(),
                any()
        )).thenReturn(rows0, rows1);

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
        var out = admin.listRecent("t", 10);

        assertEquals(2, out.size());
        assertEquals("5-0", out.get(0).getId()); // higher timestamp first
        assertEquals(1, out.get(0).getPartitionId());
        assertEquals(Map.of("x", "y"), out.get(0).getFields());
    }
}
