package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.PendingEntry;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.clearInvocations;

/**
 * Covers RedisMessageQueueAdmin.parseId forms via range(), pending-sort comparators
 * (incl. the ID-sort lambda) and the catch/fallback branches of the admin operations.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisMessageQueueAdminParseIdAndErrorPathsTest {

    private RedissonClient client;
    private TopicRegistry topicRegistry;
    private TopicPartitionRegistry partitionRegistry;
    private PayloadLifecycleManager payloadLifecycleManager;
    private RStream<String, Object> stream;
    private RedisMessageQueueAdmin admin;

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        topicRegistry = mock(TopicRegistry.class);
        partitionRegistry = mock(TopicPartitionRegistry.class);
        payloadLifecycleManager = mock(PayloadLifecycleManager.class);
        stream = mock(RStream.class);
        when(partitionRegistry.getPartitionCount(anyString())).thenReturn(1);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE)))
                .thenReturn((RStream) stream);
        admin = new RedisMessageQueueAdmin(client, topicRegistry, partitionRegistry, payloadLifecycleManager);
    }

    private Map<StreamMessageId, Map<String, Object>> oneEntry() {
        Map<StreamMessageId, Map<String, Object>> out = new LinkedHashMap<>();
        out.put(new StreamMessageId(1, 0), new HashMap<>());
        return out;
    }

    // ===== parseId via range() =====

    @Test
    void rangeParsesAllIdForms() {
        when(stream.isExists()).thenReturn(true);
        when(stream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenReturn(oneEntry());

        assertDoesNotThrow(() -> admin.range("t", 0, "10-5", "20-7", 10, false));
        verify(stream).range(eq(10), eq(new StreamMessageId(10, 5)), eq(new StreamMessageId(20, 7)));

        clearInvocations(stream);
        assertDoesNotThrow(() -> admin.range("t", 0, "0-0", "99", 5, false));
        verify(stream).range(eq(5), eq(StreamMessageId.MIN), eq(new StreamMessageId(99)));

        clearInvocations(stream);
        assertDoesNotThrow(() -> admin.range("t", 0, "-", "$", 5, false));
        verify(stream).range(eq(5), eq(StreamMessageId.MIN), eq(StreamMessageId.MAX));

        clearInvocations(stream);
        assertDoesNotThrow(() -> admin.range("t", 0, "0", "+", 5, false));
        verify(stream).range(eq(5), eq(StreamMessageId.MIN), eq(StreamMessageId.MAX));

        clearInvocations(stream);
        assertDoesNotThrow(() -> admin.range("t", 0, "bad-id", "worse-id", 5, false));
        verify(stream).range(eq(5), eq(StreamMessageId.MIN), eq(StreamMessageId.MAX));

        clearInvocations(stream);
        assertDoesNotThrow(() -> admin.range("t", 0, null, "", 5, false));
        verify(stream, atLeastOnce()).range(eq(5), eq(StreamMessageId.MIN), eq(StreamMessageId.MAX));
    }

    @Test
    void rangeReverseAndMissingStreamReturnEmpty() {
        when(stream.isExists()).thenReturn(false);
        assertTrue(admin.range("t", 0, "1-0", "2-0", 5, false).isEmpty());

        when(stream.isExists()).thenReturn(true);
        when(client.getScript(any(org.redisson.client.codec.Codec.class)))
                .thenReturn(mock(org.redisson.api.RScript.class));
        assertDoesNotThrow(() -> admin.range("t", 0, "5-1", "9-1", 5, true));
        assertDoesNotThrow(() -> admin.range("t", 0, null, null, 5, true));
    }

    // ===== pending sort comparators incl. ID lambda =====

    private PendingEntry entry(String id, String consumer, long idle, long deliveries) {
        PendingEntry p = mock(PendingEntry.class);
        when(p.getId()).thenReturn(parseId(id));
        when(p.getConsumerName()).thenReturn(consumer);
        when(p.getIdleTime()).thenReturn(idle);
        when(p.getDeliveryCount()).thenReturn(deliveries);
        return p;
    }

    private StreamMessageId parseId(String s) {
        String[] parts = s.split("-");
        return new StreamMessageId(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    @Test
    void pendingSortByIdInvokesComparatorLambda() {
        when(stream.isExists()).thenReturn(true);
        List<PendingEntry> entries = List.of(
                entry("3-0", "c1", 500, 5),
                entry("1-0", "c2", 100, 1),
                entry("2-0", "c3", 300, 9));
        when(stream.listPending(anyString(), any(), any(), anyInt())).thenReturn(entries);

        var asc = admin.getPendingMessages("t", "g", 10, PendingSort.ID, false, 0);
        assertEquals(3, asc.size());
        assertEquals(new StreamMessageId(1, 0), asc.get(0).getMessageId());
        assertEquals(new StreamMessageId(3, 0), asc.get(2).getMessageId());

        var desc = admin.getPendingMessages("t", "g", 2, PendingSort.ID, true, 0);
        assertEquals(2, desc.size());
        assertEquals(new StreamMessageId(3, 0), desc.get(0).getMessageId());
    }

    @Test
    void pendingSortByDeliveriesAndIdleWithMinIdleFilter() {
        when(stream.isExists()).thenReturn(true);
        List<PendingEntry> entries = List.of(
                entry("3-0", "c1", 500, 5),
                entry("1-0", "c2", 100, 1),
                entry("2-0", "c3", 300, 9));
        when(stream.listPending(anyString(), any(), any(), anyInt())).thenReturn(entries);

        var byDeliveries = admin.getPendingMessages("t", "g", 10, PendingSort.DELIVERIES, true, 0);
        assertEquals(new StreamMessageId(2, 0), byDeliveries.get(0).getMessageId());

        var byIdleFiltered = admin.getPendingMessages("t", "g", 10, PendingSort.IDLE, false, 250);
        assertEquals(2, byIdleFiltered.size());
        assertEquals(new StreamMessageId(2, 0), byIdleFiltered.get(0).getMessageId());

        // 3-arg overload delegates with IDLE/true defaults
        var delegated = admin.getPendingMessages("t", "g", 1);
        assertEquals(1, delegated.size());
    }

    // ===== catch / fallback branches =====

    @Test
    void listAllTopicsSwallowsRegistryFailure() {
        doThrow(new IllegalStateException("registry down")).when(topicRegistry).getAllTopics();
        assertTrue(admin.listAllTopics().isEmpty());

        doReturn(new java.util.LinkedHashSet<>(List.of("a", "b"))).when(topicRegistry).getAllTopics();
        assertEquals(List.of("a", "b"), admin.listAllTopics());
    }

    @Test
    void getQueueInfoErrorPaths() {
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenThrow(new IllegalStateException("redis down"));
        var info = admin.getQueueInfo("t");
        assertFalse(info.isExists());

        // multi-partition path (pc>1)
        RedissonClient c2 = mock(RedissonClient.class);
        RedisMessageQueueAdmin admin2 = new RedisMessageQueueAdmin(c2, topicRegistry,
                mockMultiPartitionRegistry(3), payloadLifecycleManager);
        RStream<String, Object> s0 = mock(RStream.class);
        when(c2.getStream(eq(StreamKeys.partitionStream("t2", 0)), eq(StringCodec.INSTANCE)))
                .thenReturn((RStream) s0);
        RStream<String, Object> s1 = mock(RStream.class);
        when(c2.getStream(eq(StreamKeys.partitionStream("t2", 1)), eq(StringCodec.INSTANCE)))
                .thenReturn((RStream) s1);
        RStream<String, Object> s2 = mock(RStream.class);
        when(c2.getStream(eq(StreamKeys.partitionStream("t2", 2)), eq(StringCodec.INSTANCE)))
                .thenReturn((RStream) s2);
        when(s0.isExists()).thenReturn(true);
        when(s0.size()).thenReturn(4L);
        org.redisson.api.stream.StreamInfo<String, Object> si = new org.redisson.api.stream.StreamInfo<>();
        si.setGroups(1);
        si.setLastGeneratedId(new StreamMessageId(100, 0));
        when(s0.getInfo()).thenReturn(si);
        var multi = admin2.getQueueInfo("t2");
        assertTrue(multi.isExists());
        assertEquals(4L, multi.getLength());
    }

    private TopicPartitionRegistry mockMultiPartitionRegistry(int pc) {
        TopicPartitionRegistry reg = mock(TopicPartitionRegistry.class);
        when(reg.getPartitionCount(anyString())).thenReturn(pc);
        return reg;
    }

    @Test
    void operationCatchesAreSafe() {
        when(partitionRegistry.getPartitionCount(anyString())).thenThrow(new IllegalStateException("x"));
        assertTrue(admin.getConsumerGroups("t").isEmpty());
        assertNull(admin.getConsumerGroupStats("t", "g"));
        assertFalse(admin.consumerGroupExists("t", "g"));
        assertEquals(0, admin.getPendingCount("t", "g"));
        assertEquals(0, admin.trimQueue("t", 10));
        assertEquals(0, admin.trimQueueByAge("t", java.time.Duration.ofMinutes(1)));
        assertFalse(admin.deleteConsumerGroup("t", "g"));
        assertFalse(admin.resetConsumerGroupOffset("t", "g", "0"));
        assertFalse(admin.updatePartitionCount("t", 2));
        assertTrue(admin.listRecent("t", 5).isEmpty());
        assertTrue(admin.range("t", 0, "1-0", "2-0", 5, false).isEmpty());
    }

    @Test
    void deleteTopicCatchesKeyFailure() {
        RKeys keys = mock(RKeys.class);
        when(client.getKeys()).thenReturn(keys);
        when(keys.delete(anyString())).thenThrow(new IllegalStateException("del failed"));
        when(payloadLifecycleManager.cleanupTopicPayloadHashes(anyString())).thenReturn(0L);
        assertFalse(admin.deleteTopic("t"));
        verify(topicRegistry).unregisterTopic("t");
    }

    @Test
    void resetConsumerGroupOffsetRejectsBrokenId() {
        assertFalse(admin.resetConsumerGroupOffset("t", "g", null));
        assertFalse(admin.resetConsumerGroupOffset("t", "g", "1-2-3-4-5"));
    }

    @Test
    void updatePartitionCountHandlesFalseAndThrow() {
        when(partitionRegistry.updatePartitionCount("t", 2)).thenReturn(false);
        assertFalse(admin.updatePartitionCount("t", 2));
        when(partitionRegistry.updatePartitionCount("t", 3)).thenThrow(new IllegalStateException("x"));
        assertFalse(admin.updatePartitionCount("t", 3));
    }

    @Test
    void topicExistsCatchesAndChecksAllPartitions() {
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenThrow(new IllegalStateException("x"));
        assertFalse(admin.topicExists("t"));
    }

    @Test
    void getPendingMessagesSortedSwallowsStreamFailure() {
        when(stream.isExists()).thenReturn(true);
        when(stream.listPending(anyString(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("xpending failed"));
        assertTrue(admin.getPendingMessages("t", "g", 5, PendingSort.ID, true, 0).isEmpty());
    }
}
