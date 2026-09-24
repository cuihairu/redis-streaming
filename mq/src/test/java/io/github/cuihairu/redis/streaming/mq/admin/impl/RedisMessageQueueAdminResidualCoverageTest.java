package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.admin.model.MessageEntry;
import io.github.cuihairu.redis.streaming.mq.admin.model.PendingMessage;
import io.github.cuihairu.redis.streaming.mq.admin.model.PendingSort;
import io.github.cuihairu.redis.streaming.mq.admin.model.QueueInfo;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamGroup;
import org.redisson.api.stream.StreamInfo;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for RedisMessageQueueAdmin error/defensive branches using the
 * package-private constructor to inject failing collaborators.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisMessageQueueAdminResidualCoverageTest {

    private RedissonClient client;
    private TopicRegistry topicRegistry;
    private TopicPartitionRegistry partitionRegistry;
    private PayloadLifecycleManager payloadLifecycleManager;
    private RScript script;
    private RedisMessageQueueAdmin admin;

    /** Overrides the sorted overload so the 3-arg bridge's catch block is reachable. */
    static class ThrowingPendingAdmin extends RedisMessageQueueAdmin {
        ThrowingPendingAdmin(RedissonClient c, TopicRegistry t, TopicPartitionRegistry p, PayloadLifecycleManager l) {
            super(c, t, p, l);
        }

        @Override
        public List<PendingMessage> getPendingMessages(String topic, String group, int limit,
                                                       PendingSort sort, boolean desc, long minIdleMs) {
            throw new IllegalStateException("sorted pending boom");
        }
    }

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        topicRegistry = mock(TopicRegistry.class);
        partitionRegistry = mock(TopicPartitionRegistry.class);
        payloadLifecycleManager = mock(PayloadLifecycleManager.class);
        script = mock(RScript.class);
        when(client.getScript(any(Codec.class))).thenReturn(script);
        admin = new RedisMessageQueueAdmin(client, topicRegistry, partitionRegistry, payloadLifecycleManager);
    }

    private RStream<String, Object> stream(boolean exists) {
        RStream<String, Object> s = mock(RStream.class);
        when(s.isExists()).thenReturn(exists);
        return s;
    }

    @Test
    void getQueueInfoHandlesNullGeneratedIds() {
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        RStream<String, Object> s = stream(true);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(Codec.class))).thenReturn((RStream) s);
        when(s.size()).thenReturn(3L);
        StreamInfo<String, Object> info = new StreamInfo<>();
        when(s.getInfo()).thenReturn(info);

        QueueInfo qi = admin.getQueueInfo("t");
        assertTrue(qi.isExists());
        assertNull(qi.getFirstMessageId());
        assertNull(qi.getLastMessageId());
        assertNull(qi.getLastUpdatedAt());
    }

    @Test
    void getQueueInfoMultiPartitionWithNullLastId() {
        when(partitionRegistry.getPartitionCount("t")).thenReturn(2);
        RStream<String, Object> s0 = stream(true);
        RStream<String, Object> s1 = stream(false);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(Codec.class))).thenReturn((RStream) s0);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 1)), any(Codec.class))).thenReturn((RStream) s1);
        when(s0.size()).thenReturn(1L);
        when(s0.getInfo()).thenReturn(new StreamInfo<>());

        QueueInfo qi = admin.getQueueInfo("t");
        assertTrue(qi.isExists());
        assertNull(qi.getLastUpdatedAt());
    }

    @Test
    void topicExistsFalseWhenNoPartitionStreams() {
        when(partitionRegistry.getPartitionCount("t")).thenReturn(2);
        RStream<String, Object> s0 = stream(false);
        RStream<String, Object> s1 = stream(false);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(Codec.class))).thenReturn((RStream) s0);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 1)), any(Codec.class))).thenReturn((RStream) s1);
        assertFalse(admin.topicExists("t"));
    }

    @Test
    void getConsumerGroupsSkipsMissingPartitions() {
        when(partitionRegistry.getPartitionCount("t")).thenReturn(2);
        RStream<String, Object> s0 = stream(true);
        RStream<String, Object> s1 = stream(false);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(Codec.class))).thenReturn((RStream) s0);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 1)), any(Codec.class))).thenReturn((RStream) s1);
        StreamGroup g = new StreamGroup("g", 1, 2, null);
        when(s0.listGroups()).thenReturn(List.of(g));

        assertEquals(1, admin.getConsumerGroups("t").size());
    }

    @Test
    void pendingMessagesBridgeCatchesSortedOverloadFailure() {
        ThrowingPendingAdmin throwing = new ThrowingPendingAdmin(client, topicRegistry, partitionRegistry, payloadLifecycleManager);
        assertTrue(throwing.getPendingMessages("t", "g", 5).isEmpty());
    }

    @Test
    void trimQueueByAgeSkipsMissingStreams() {
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        RStream<String, Object> s = stream(false);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(Codec.class))).thenReturn((RStream) s);
        assertEquals(0, admin.trimQueueByAge("t", Duration.ofMinutes(5)));
    }

    @Test
    void deleteTopicSwallowsPartitionRegistryFailure() {
        when(partitionRegistry.getPartitionCount("t")).thenThrow(new IllegalStateException("meta boom"));
        assertFalse(admin.deleteTopic("t"));
    }

    @Test
    void deleteConsumerGroupToleratesRemoveGroupFailure() {
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        RStream<String, Object> s = stream(true);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(Codec.class))).thenReturn((RStream) s);
        doThrow(new IllegalStateException("group boom")).when(s).removeGroup("g");
        assertTrue(admin.deleteConsumerGroup("t", "g"));
        verify(s).removeGroup("g");
    }

    @Test
    void listRecentSkipsMalformedRows() {
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        RStream<String, Object> s = stream(true);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(Codec.class))).thenReturn((RStream) s);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any()))
                .thenReturn(Arrays.asList("not-a-list", List.of("only-id"), List.of("5-0", List.of("k", "v"))));

        List<MessageEntry> entries = admin.listRecent("t", 5);
        assertEquals(1, entries.size());
        assertEquals("5-0", entries.get(0).getId());
    }

    @Test
    void rangeReverseSkipsMalformedRows() {
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        RStream<String, Object> s = stream(true);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(Codec.class))).thenReturn((RStream) s);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(), any(), any()))
                .thenReturn(Arrays.asList("junk", List.of("1-0"), List.of("2-0", List.of("a", "b"))));

        List<MessageEntry> entries = admin.range("t", 0, null, null, 5, true);
        assertEquals(1, entries.size());
        assertEquals("2-0", entries.get(0).getId());
    }
}
