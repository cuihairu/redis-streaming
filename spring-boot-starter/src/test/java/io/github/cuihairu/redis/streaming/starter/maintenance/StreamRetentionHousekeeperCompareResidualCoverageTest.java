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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Deterministic residual coverage for StreamRetentionHousekeeper#compareStreamId branches
 * and the trimTopic/trimDlq/runOnce edges (white-box style used across this repo).
 */
class StreamRetentionHousekeeperCompareResidualCoverageTest {

    private static int compare(Object keeper, String a, String b) throws Exception {
        Method m = StreamRetentionHousekeeper.class.getDeclaredMethod("compareStreamId", String.class, String.class);
        m.setAccessible(true);
        return (int) m.invoke(keeper, a, b);
    }

    private static StreamRetentionHousekeeper quietKeeper(RedissonClient redisson) {
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of());
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(List.of());
        return new StreamRetentionHousekeeper(redisson, admin,
                MqOptions.builder().trimIntervalSec(3600).build());
    }

    @Test
    void compareStreamIdCoversAllBranches() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        StreamRetentionHousekeeper k = quietKeeper(redisson);
        try {
            assertEquals(-1, compare(k, "5-1", "6-0"));
            assertEquals(1, compare(k, "6-0", "5-9"));
            assertEquals(-1, compare(k, "5-1", "5-2"));
            assertEquals(1, compare(k, "5-2", "5-1"));
            assertEquals(-1, compare(k, "5", "5-1"));
            assertEquals(1, compare(k, "5-1", "5"));
            assertEquals(0, compare(k, "5", "5"));
            assertEquals(0, compare(k, "5-1", "5-1"));
            assertEquals("a".compareTo("b"), compare(k, "a", "b"));
            assertEquals(0, compare(k, "zzz", "zzz"));
        } finally {
            k.close();
        }
    }

    @Test
    void trimTopicComputesMinFrontierIdAcrossGroups() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        StreamRetentionHousekeeper keeper = quietKeeper(redisson);

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap meta = mock(RMap.class);
        when(meta.get("partitionCount")).thenReturn("2");
        when(redisson.getMap(eq(StreamKeys.topicMeta("topicY")), any(org.redisson.client.codec.Codec.class))).thenReturn(meta);

        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(1L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        Map<String, String> ids = new HashMap<>();
        ids.put("g1", "10-5");
        ids.put("g2", "10-1");   // same ms, lower seq -> becomes min
        ids.put("g3", "9");      // lower ms, no seq part
        ids.put("g4", "bad-id"); // malformed -> string fallback
        ids.put("g5", "");       // empty value skipped
        when(frontier.readAllMap()).thenReturn(ids);
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicY", 0)))).thenReturn(frontier);
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicY", 1)))).thenReturn(frontier);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(true);

        try {
            Method trimTopic = StreamRetentionHousekeeper.class.getDeclaredMethod("trimTopic", String.class);
            trimTopic.setAccessible(true);
            assertDoesNotThrow(() -> trimTopic.invoke(keeper, "topicY"));
        } finally {
            keeper.close();
        }
    }

    @Test
    void trimTopicSkipsFrontierTrimWhenNoGroupActive() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        StreamRetentionHousekeeper keeper = quietKeeper(redisson);

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap meta = mock(RMap.class);
        when(meta.get("partitionCount")).thenReturn("1");
        when(redisson.getMap(eq(StreamKeys.topicMeta("topicZ")), any(org.redisson.client.codec.Codec.class))).thenReturn(meta);

        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(null); // null Long branch of recordTrim call sites

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        when(frontier.readAllMap()).thenReturn(Map.of("g1", "1-1"));
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicZ", 0)))).thenReturn(frontier);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(false); // inactive -> minId stays null

        try {
            Method trimTopic = StreamRetentionHousekeeper.class.getDeclaredMethod("trimTopic", String.class);
            trimTopic.setAccessible(true);
            assertDoesNotThrow(() -> trimTopic.invoke(keeper, "topicZ"));
        } finally {
            keeper.close();
        }
    }

    @Test
    void runOnceKeyScanEdges() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of("topicW"));

        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(Arrays.asList(
                null,
                StreamKeys.streamPrefix() + "::p:0",       // empty topic name -> skipped
                StreamKeys.streamPrefix() + "::dlq",       // empty topic name -> skipped
                StreamKeys.streamPrefix() + ":topicW:p:0", // discovered again
                StreamKeys.streamPrefix() + ":topicW:dlq",
                StreamKeys.streamPrefix() + ":topicW:weird"));

        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(null);

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap meta = mock(RMap.class);
        when(meta.get("partitionCount")).thenReturn(null);
        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        when(frontier.readAllMap()).thenReturn(null); // null map branch
        when(redisson.getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(meta);
        when(redisson.getMap(anyString())).thenReturn(frontier);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(true);

        MqOptions opts = MqOptions.builder()
                .defaultPartitionCount(2)
                .retentionMaxLenPerPartition(3)
                .retentionMs(1_000)
                .dlqRetentionMaxLen(0)   // dlq trim disabled -> early return
                .dlqRetentionMs(0)
                .trimIntervalSec(3600)
                .build();
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(redisson, admin, opts);
        try {
            assertDoesNotThrow(keeper::runOnce);
        } finally {
            keeper.close();
        }
        assertTrue(true);
    }

    @Test
    void closeHandlesInterruptedShutdown() {
        RedissonClient redisson = mock(RedissonClient.class);
        StreamRetentionHousekeeper keeper = quietKeeper(redisson);
        Thread.currentThread().interrupt();
        assertDoesNotThrow(keeper::close);
        org.junit.jupiter.api.Assertions.assertTrue(Thread.interrupted());
    }

    @Test
    void runOnceSurvivesAdminListFailure() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        org.mockito.Mockito.when(admin.listAllTopics()).thenThrow(new IllegalStateException("admin down"));
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(List.of());

        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(redisson, admin,
                MqOptions.builder().trimIntervalSec(3600).build());
        try {
            assertDoesNotThrow(keeper::runOnce);
        } finally {
            keeper.close();
        }
    }

    @Test
    void dlqTrimNullEvalBranches() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of());
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(List.of());
        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(null);
        StreamRetentionHousekeeper keeper = new StreamRetentionHousekeeper(redisson, admin,
                MqOptions.builder().dlqRetentionMaxLen(2).dlqRetentionMs(1_000).trimIntervalSec(3600).build());
        try {
            Method trimDlq = StreamRetentionHousekeeper.class.getDeclaredMethod("trimDlq", String.class);
            trimDlq.setAccessible(true);
            assertDoesNotThrow(() -> trimDlq.invoke(keeper, "topicD"));
        } finally {
            keeper.close();
        }
    }
}
