package io.github.cuihairu.redis.streaming.mq.admin.impl;

import io.github.cuihairu.redis.streaming.mq.admin.TopicRegistry;
import io.github.cuihairu.redis.streaming.mq.impl.PayloadLifecycleManager;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MQ-13 regression: trimQueueByAge() did range(Integer.MAX_VALUE, MIN, end) in ONE call,
 * deserializing every aged-out entry (values included) into the heap before deleting them
 * — an OOM risk on a large stream. The scan must page with a bounded count and an id cursor.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class TrimQueueByAgePagingTest {

    @BeforeAll
    static void seam() {
        System.setProperty("mq.admin.test.trimAgePageSize", "2");
    }

    @AfterAll
    static void restore() {
        System.clearProperty("mq.admin.test.trimAgePageSize");
    }

    private static Map<StreamMessageId, Map<String, Object>> page(StreamMessageId... ids) {
        Map<StreamMessageId, Map<String, Object>> m = new LinkedHashMap<>();
        for (StreamMessageId id : ids) m.put(id, Map.of("payload", "x"));
        return m;
    }

    @Test
    void trimQueueByAgePagesTheIdScanInsteadOfLoadingTheWholeAgeRange() {
        RedissonClient client = mock(RedissonClient.class);
        TopicPartitionRegistry partitionRegistry = mock(TopicPartitionRegistry.class);
        when(partitionRegistry.getPartitionCount("t")).thenReturn(1);
        RStream<String, Object> stream = mock(RStream.class);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), eq(StringCodec.INSTANCE))).thenReturn((RStream) stream);
        when(stream.isExists()).thenReturn(true);

        AtomicInteger calls = new AtomicInteger();
        when(stream.range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class))).thenAnswer(inv -> {
            switch (calls.incrementAndGet()) {
                case 1: return page(new StreamMessageId(1, 0), new StreamMessageId(2, 0));
                case 2: return page(new StreamMessageId(2, 1), new StreamMessageId(3, 0));
                case 3: return page(new StreamMessageId(4, 0));   // short page -> end of scan
                default: return Map.of();
            }
        });

        RedisMessageQueueAdmin admin = new RedisMessageQueueAdmin(client,
                mock(TopicRegistry.class), partitionRegistry, mock(PayloadLifecycleManager.class));
        long deleted = admin.trimQueueByAge("t", Duration.ofMinutes(5));

        // MQ-13, stated first so the old-code failure is exactly the unbounded scan:
        // old code made ONE range() call with count = Integer.MAX_VALUE
        ArgumentCaptor<Integer> counts = ArgumentCaptor.forClass(Integer.class);
        verify(stream, atLeastOnce()).range(counts.capture(), any(StreamMessageId.class), any(StreamMessageId.class));
        List<Integer> seen = counts.getAllValues();
        for (int count : seen) {
            assertTrue(count <= 2, "every range page must be bounded by the page size, got " + seen);
        }
        // all 5 aged ids deleted across 3 bounded pages
        assertEquals(5L, deleted);
        verify(stream, times(5)).remove(any(StreamMessageId.class));
    }
}
