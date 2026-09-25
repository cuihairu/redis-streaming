package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.admin.model.ConsumerGroupStats;
import io.github.cuihairu.redis.streaming.mq.admin.model.MessageEntry;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.redisson.api.RKeys;
import org.redisson.api.RScript;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamGroup;
import org.redisson.api.stream.StreamInfo;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link RedisMessageQueueAdmin}: group stats with missing ids,
 * delete/reset error paths and raw-peek parsing edge cases.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisMessageQueueAdminSprintCoverageTest {

    private RedissonClient redisson;
    private TopicRegistry topicRegistry;
    private TopicPartitionRegistry partitionRegistry;
    private PayloadLifecycleManager payloadLifecycleManager;
    private RStream<String, Object> stream;
    private RScript script;
    private RKeys keys;

    private RedisMessageQueueAdmin newAdmin() {
        return new RedisMessageQueueAdmin(redisson, topicRegistry, partitionRegistry, payloadLifecycleManager);
    }

    private void baseMocks() {
        redisson = mock(RedissonClient.class);
        topicRegistry = mock(TopicRegistry.class);
        partitionRegistry = mock(TopicPartitionRegistry.class);
        payloadLifecycleManager = mock(PayloadLifecycleManager.class);
        stream = mock(RStream.class);
        script = evalScript();
        keys = mock(RKeys.class);
        when(redisson.getStream(anyString(), any())).thenReturn((RStream) stream);
        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(redisson.getKeys()).thenReturn(keys);
    }

    private final java.util.LinkedList<Object> evalResults = new java.util.LinkedList<>();

    private void queueEval(Object... results) {
        for (Object r : results) {
            evalResults.add(r);
        }
    }

    @SuppressWarnings("unchecked")
    private RScript evalScript() {
        return mock(RScript.class, inv -> {
            if ("eval".equals(inv.getMethod().getName())) {
                Object next = evalResults.poll();
                if (next instanceof RuntimeException re) {
                    throw re;
                }
                return next;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(inv);
        });
    }

    // ===== getConsumerGroupStats null-id combinations =====

    @Test
    void groupStatsToleratesNullLastGeneratedId() throws Exception {
        baseMocks();
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(stream.isExists()).thenReturn(true);
        StreamGroup g = new StreamGroup("g", 1, 2, new StreamMessageId(5, 0));
        when(stream.listGroups()).thenReturn(List.of(g));
        StreamInfo<String, Object> info = new StreamInfo<>();
        info.setLastGeneratedId(null);
        when(stream.getInfo()).thenReturn(info);

        ConsumerGroupStats stats = newAdmin().getConsumerGroupStats("t", "g");
        assertEquals(2, stats.getPendingCount());
        assertEquals(0, stats.getLag());
    }

    @Test
    void groupStatsToleratesNullLastDeliveredId() throws Exception {
        baseMocks();
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(stream.isExists()).thenReturn(true);
        StreamGroup g = new StreamGroup("g", 1, 2, null);
        when(stream.listGroups()).thenReturn(List.of(g));
        StreamInfo<String, Object> info = new StreamInfo<>();
        info.setLastGeneratedId(new StreamMessageId(9, 0));
        when(stream.getInfo()).thenReturn(info);

        ConsumerGroupStats stats = newAdmin().getConsumerGroupStats("t", "g");
        assertEquals(2, stats.getPendingCount());
        assertEquals(0, stats.getLag());
    }

    // ===== deleteTopic returns false when nothing was removed =====

    @Test
    void deleteTopicReturnsFalseWhenNoKeysOrPayloadsRemoved() throws Exception {
        baseMocks();
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(keys.delete(anyString())).thenReturn(0L);
        when(payloadLifecycleManager.cleanupTopicPayloadHashes("t")).thenReturn(0L);

        assertFalse(newAdmin().deleteTopic("t"));
        verify(topicRegistry).unregisterTopic("t");
    }

    // ===== deleteConsumerGroup scan edge cases =====

    @Test
    void deleteConsumerGroupSkipsNullAndForeignKeys() throws Exception {
        baseMocks();
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(stream.isExists()).thenReturn(true);
        List<String> all = new ArrayList<>();
        all.add(null);
        all.add("totally-unrelated:key");
        all.add(StreamKeys.partitionStream("t", 7));
        when(keys.getKeys()).thenReturn(all);

        assertTrue(newAdmin().deleteConsumerGroup("t", "g"));
        verify(stream, times(2)).removeGroup("g");
    }

    @Test
    void deleteConsumerGroupSwallowsScanFailureFromStreamPrefixSeam() throws Exception {
        baseMocks();
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        try (MockedStatic<StreamKeys> mocked = mockStatic(StreamKeys.class)) {
            mocked.when(() -> StreamKeys.partitionStream(anyString(), anyInt()))
                    .thenAnswer(inv -> "stream:topic:" + inv.getArgument(0) + ":p:" + inv.getArgument(1));
            mocked.when(StreamKeys::streamPrefix).thenThrow(new IllegalStateException("prefix boom"));
            assertDoesNotThrow(() -> assertFalse(newAdmin().deleteConsumerGroup("t", "g")));
        }
    }

    // ===== resetConsumerGroupOffset createGroup failure classification =====

    @Test
    void resetOffsetToleratesNonBusyGroupCreateFailures() throws Exception {
        baseMocks();
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        doThrow(new IllegalStateException("WRONGTYPE not a stream")).when(stream)
                .createGroup(any(StreamCreateGroupArgs.class));
        assertTrue(newAdmin().resetConsumerGroupOffset("t", "g", "1-1"));

        doThrow(new IllegalStateException((String) null)).when(stream).createGroup(any(StreamCreateGroupArgs.class));
        assertTrue(newAdmin().resetConsumerGroupOffset("t", "g", "1-1"));
    }

    // ===== listRecent row parsing =====

    @Test
    void listRecentToleratesNullRowsAndMalformedPairs() throws Exception {
        baseMocks();
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(stream.isExists()).thenReturn(true);
        queueEval(null, Arrays.asList(
                        "not-a-pair",
                        Arrays.asList("only-id"),
                        Arrays.asList("5-0", Map.of("f", "v")),
                        Arrays.asList("6-0", Arrays.asList("k", "v", null, "x", "y", null)),
                        Arrays.asList("7-0", Map.of("ignored", "map"))));

        List<MessageEntry> first = newAdmin().listRecent("t", 5);
        assertTrue(first.isEmpty());

        List<MessageEntry> second = newAdmin().listRecent("t", 5);
        assertEquals(3, second.size());
        MessageEntry withMap = second.stream().filter(e -> e.getId().equals("5-0")).findFirst().orElseThrow();
        assertTrue(withMap.getFields().isEmpty(), "non-list field container is skipped");
        MessageEntry withList = second.stream().filter(e -> e.getId().equals("6-0")).findFirst().orElseThrow();
        assertEquals(1, withList.getFields().size(), "null key/value pairs are dropped");
        assertEquals("v", withList.getFields().get("k"));
        MessageEntry nonList = second.stream().filter(e -> e.getId().equals("7-0")).findFirst().orElseThrow();
        assertTrue(nonList.getFields().isEmpty());
    }

    // ===== range reverse path parsing =====

    @Test
    void rangeReverseDefaultsAndMalformedRows() throws Exception {
        baseMocks();
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(stream.isExists()).thenReturn(true);
        Object rows = Arrays.asList(
                "junk",
                Arrays.asList("5-0", Map.of("f", "v")),
                Arrays.asList("6-0", Arrays.asList("k", "v", null, "x")),
                Arrays.asList("7-0", Map.of("ignored", "map")));
        queueEval(rows, rows);

        List<MessageEntry> out = newAdmin().range("t", 0, null, "  ", 10, true);
        assertEquals(3, out.size());
        MessageEntry withMap = out.stream().filter(e -> e.getId().equals("5-0")).findFirst().orElseThrow();
        assertTrue(withMap.getFields().isEmpty(), "non-list field container is skipped");
        MessageEntry withList = out.stream().filter(e -> e.getId().equals("6-0")).findFirst().orElseThrow();
        assertEquals(1, withList.getFields().size(), "null key/value pairs are dropped");
        assertEquals("v", withList.getFields().get("k"));
        MessageEntry nonList = out.stream().filter(e -> e.getId().equals("7-0")).findFirst().orElseThrow();
        assertTrue(nonList.getFields().isEmpty());

        assertEquals(3, newAdmin().range("t", 0, "", "", 10, true).size(), "blank bounds fall back to full reverse range");
    }

    @Test
    void rangeReversePassesExplicitBoundsThrough() throws Exception {
        baseMocks();
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        when(stream.isExists()).thenReturn(true);
        queueEval(List.of());

        assertTrue(newAdmin().range("t", 0, "1-1", "9-9", 10, true).isEmpty());
    }
}
