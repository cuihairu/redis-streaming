package io.github.cuihairu.redis.streaming.starter.maintenance;

import io.github.cuihairu.redis.streaming.mq.admin.MessageQueueAdmin;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual coverage for StreamRetentionHousekeeper: DLQ retention toggles, frontier map
 * null/empty guards, null/empty frontier values and the null-deleted-count fallback.
 *
 * <p>All stubbing is installed before the housekeeper is constructed because its constructor
 * immediately schedules one {@code runOnce()} on a background thread.</p>
 */
@Timeout(60)
class StreamRetentionHousekeeperDlqAndFrontierResidualTest {

    private static MessageQueueAdmin quietAdmin(RedissonClient redisson) {
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of());
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.getKeys()).thenReturn(List.of());
        return admin;
    }

    private static StreamRetentionHousekeeper keeper(RedissonClient redisson, MessageQueueAdmin admin,
                                                     MqOptions options) {
        return new StreamRetentionHousekeeper(redisson, admin, options);
    }

    private static void invokeTrim(StreamRetentionHousekeeper keeper, String method, String topic) throws Exception {
        Method m = StreamRetentionHousekeeper.class.getDeclaredMethod(method, String.class);
        m.setAccessible(true);
        m.invoke(keeper, topic);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RMap mockMeta(RedissonClient redisson, String topic, String partitionCount) {
        RMap meta = mock(RMap.class);
        when(meta.get("partitionCount")).thenReturn(partitionCount);
        when(redisson.getMap(eq(StreamKeys.topicMeta(topic)), any(org.redisson.client.codec.Codec.class)))
                .thenReturn(meta);
        return meta;
    }

    @Test
    void trimDlqIsSkippedWhenNoDlqRetentionConfigured() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = quietAdmin(redisson);
        StreamRetentionHousekeeper k = keeper(redisson, admin, MqOptions.builder()
                .trimIntervalSec(3600).dlqRetentionMaxLen(0).dlqRetentionMs(0).build());
        try {
            invokeTrim(k, "trimDlq", "topicA");
            verify(redisson, never()).getScript();
        } finally {
            k.close();
        }
    }

    @Test
    void trimDlqAppliesBothMaxLenAndMinIdWhenConfigured() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = quietAdmin(redisson);
        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(null);

        StreamRetentionHousekeeper k = keeper(redisson, admin, MqOptions.builder()
                .trimIntervalSec(3600).dlqRetentionMaxLen(7).dlqRetentionMs(1000).build());
        try {
            invokeTrim(k, "trimDlq", "topicA");
            ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
            verify(script, org.mockito.Mockito.times(2))
                    .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                            eq(List.of(StreamKeys.dlq("topicA"))), arg.capture());
            List<Object> argv = arg.getAllValues();
            assertEquals("7", argv.get(0), "MAXLEN uses the configured dlq retention length");
            assertTrue(argv.get(1).toString().endsWith("-0"), "MINID uses a timestamp-based stream id");
        } finally {
            k.close();
        }
    }

    @Test
    void trimDlqHonoursEachRetentionDimensionIndependently() throws Exception {
        RedissonClient minIdOnly = mock(RedissonClient.class);
        MessageQueueAdmin admin1 = quietAdmin(minIdOnly);
        RScript s1 = mock(RScript.class);
        when(minIdOnly.getScript()).thenReturn(s1);
        when(s1.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(0L);
        StreamRetentionHousekeeper k1 = keeper(minIdOnly, admin1, MqOptions.builder()
                .trimIntervalSec(3600).dlqRetentionMaxLen(0).dlqRetentionMs(500).build());
        try {
            invokeTrim(k1, "trimDlq", "topicA");
            verify(s1, org.mockito.Mockito.times(1))
                    .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any());
        } finally {
            k1.close();
        }

        RedissonClient maxLenOnly = mock(RedissonClient.class);
        MessageQueueAdmin admin2 = quietAdmin(maxLenOnly);
        RScript s2 = mock(RScript.class);
        when(maxLenOnly.getScript()).thenReturn(s2);
        when(s2.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(2L);
        StreamRetentionHousekeeper k2 = keeper(maxLenOnly, admin2, MqOptions.builder()
                .trimIntervalSec(3600).dlqRetentionMaxLen(3).dlqRetentionMs(0).build());
        try {
            invokeTrim(k2, "trimDlq", "topicA");
            verify(s2, org.mockito.Mockito.times(1))
                    .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any());
        } finally {
            k2.close();
        }
    }

    @Test
    void frontierTrimToleratesNullDeletedCount() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = quietAdmin(redisson);
        mockMeta(redisson, "topicF", "1");

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        Map<String, String> ids = new HashMap<>();
        ids.put("g1", "5-1");
        when(frontier.readAllMap()).thenReturn(ids);
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicF", 0)))).thenReturn(frontier);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(true);

        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(null);

        StreamRetentionHousekeeper k = keeper(redisson, admin, MqOptions.builder()
                .trimIntervalSec(3600).retentionMaxLenPerPartition(0).retentionMs(0).build());
        try {
            invokeTrim(k, "trimTopic", "topicF");
            ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
            verify(script, atLeastOnce())
                    .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                            eq(List.of(StreamKeys.partitionStream("topicF", 0))), arg.capture());
            assertEquals("5-1", arg.getValue());
        } finally {
            k.close();
        }
    }

    @Test
    void frontierValuesThatAreNullOrEmptyAreIgnored() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = quietAdmin(redisson);
        mockMeta(redisson, "topicG", "1");

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        Map<String, String> ids = new HashMap<>();
        ids.put("gNull", null);
        ids.put("gEmpty", "");
        ids.put("gReal", "7-0");
        when(frontier.readAllMap()).thenReturn(ids);
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicG", 0)))).thenReturn(frontier);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(true);

        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(1L);

        StreamRetentionHousekeeper k = keeper(redisson, admin, MqOptions.builder()
                .trimIntervalSec(3600).retentionMaxLenPerPartition(0).retentionMs(0).build());
        try {
            invokeTrim(k, "trimTopic", "topicG");
            ArgumentCaptor<Object> arg = ArgumentCaptor.forClass(Object.class);
            verify(script, atLeastOnce())
                    .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                            eq(List.of(StreamKeys.partitionStream("topicG", 0))), arg.capture());
            assertEquals("7-0", arg.getValue(), "null/empty frontier values must not become the trim id");
        } finally {
            k.close();
        }
    }

    @Test
    void frontierBlockSkippedForNullAndEmptyFrontierMaps() throws Exception {
        for (Map<String, String> frontierContent : Arrays.asList(null, new HashMap<String, String>())) {
            RedissonClient redisson = mock(RedissonClient.class);
            MessageQueueAdmin admin = quietAdmin(redisson);
            mockMeta(redisson, "topicH", "1");

            @SuppressWarnings({"unchecked", "rawtypes"})
            RMap frontier = mock(RMap.class);
            when(frontier.readAllMap()).thenReturn(frontierContent);
            when(redisson.getMap(eq(StreamKeys.commitFrontier("topicH", 0)))).thenReturn(frontier);

            RScript script = mock(RScript.class);
            when(redisson.getScript()).thenReturn(script);
            when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                    .thenReturn(1L);

            StreamRetentionHousekeeper k = keeper(redisson, admin, MqOptions.builder()
                    .trimIntervalSec(3600).retentionMaxLenPerPartition(5).retentionMs(0).build());
            try {
                invokeTrim(k, "trimTopic", "topicH");
                // only the MAXLEN trim runs; the frontier MINID trim is skipped
                verify(script, org.mockito.Mockito.times(1))
                        .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any());
                verify(redisson, never()).getBucket(anyString());
            } finally {
                k.close();
            }
        }
    }

    @Test
    void inactiveLeaseEntriesAreNotUsedForFrontierTrim() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = quietAdmin(redisson);
        mockMeta(redisson, "topicI", "1");

        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        Map<String, String> ids = new HashMap<>();
        ids.put("gGone", "1-0");
        ids.put("gLive", "9-0");
        when(frontier.readAllMap()).thenReturn(ids);
        when(redisson.getMap(eq(StreamKeys.commitFrontier("topicI", 0)))).thenReturn(frontier);

        @SuppressWarnings("unchecked")
        RBucket<Object> lease = mock(RBucket.class);
        when(redisson.getBucket(anyString())).thenReturn(lease);
        when(lease.isExists()).thenReturn(false);

        StreamRetentionHousekeeper k = keeper(redisson, admin, MqOptions.builder()
                .trimIntervalSec(3600).retentionMaxLenPerPartition(0).retentionMs(0).build());
        try {
            invokeTrim(k, "trimTopic", "topicI");
            verify(redisson, never()).getScript();
        } finally {
            k.close();
        }
    }

    @Test
    void runOnceDiscoversTopicsFromStreamKeys() {
        RedissonClient redisson = mock(RedissonClient.class);
        MessageQueueAdmin admin = mock(MessageQueueAdmin.class);
        when(admin.listAllTopics()).thenReturn(List.of());
        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        List<String> scanned = new ArrayList<>(Arrays.asList(
                null,
                StreamKeys.dlq("scanTopic"),
                StreamKeys.partitionStream("scanTopic", 0),
                "stream:topic::dlq",
                "unrelated:key"));
        when(keys.getKeys()).thenReturn(scanned);

        RScript script = mock(RScript.class);
        when(redisson.getScript()).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any()))
                .thenReturn(1L);
        mockMeta(redisson, "scanTopic", "1");
        @SuppressWarnings({"unchecked", "rawtypes"})
        RMap frontier = mock(RMap.class);
        when(frontier.readAllMap()).thenReturn(new HashMap<String, String>());
        when(redisson.getMap(eq(StreamKeys.commitFrontier("scanTopic", 0)))).thenReturn(frontier);

        StreamRetentionHousekeeper k = keeper(redisson, admin,
                MqOptions.builder().trimIntervalSec(3600).dlqRetentionMaxLen(5).build());
        try {
            assertDoesNotThrow(k::runOnce);
            // stream:topic::dlq (empty topic name) must not produce trims
            verify(script, atLeastOnce())
                    .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                            eq(List.of(StreamKeys.partitionStream("scanTopic", 0))), any());
            verify(script, atLeastOnce())
                    .eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                            eq(List.of(StreamKeys.dlq("scanTopic"))), any());
        } finally {
            k.close();
        }
    }
}
