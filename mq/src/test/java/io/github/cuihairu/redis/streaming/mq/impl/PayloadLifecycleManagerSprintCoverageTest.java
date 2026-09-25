package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link PayloadLifecycleManager}: prefix fallbacks, index
 * cleanup failure handling and delete accounting.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class PayloadLifecycleManagerSprintCoverageTest {

    private RedissonClient client;
    private RSet<String> topicIndex;
    private RScoredSortedSet<String> timeIndex;
    private RBucket<String> bucket;

    @BeforeEach
    void setUp() throws Exception {
        io.github.cuihairu.redis.streaming.mq.partition.StreamKeys.configure("streaming:mq", "stream:topic");
        client = mock(RedissonClient.class);
        topicIndex = mock(RSet.class);
        timeIndex = mock(RScoredSortedSet.class);
        bucket = mock(RBucket.class);
        when(client.getSet(anyString(), any())).thenReturn((RSet) topicIndex);
        when(client.getScoredSortedSet(anyString(), any())).thenReturn((RScoredSortedSet) timeIndex);
        when(client.getBucket(anyString(), any())).thenReturn((RBucket) bucket);
    }

    // ===== constructor prefix fallbacks =====

    @Test
    void constructorFallsBackToControlPrefixForNullKeyPrefix() throws Exception {
        MqOptions nullPrefix = new MqOptions() {
            @Override
            public String getKeyPrefix() {
                return null;
            }
        };
        when(bucket.delete()).thenReturn(true);
        RSet<String> idx = mock(RSet.class);
        when(client.getSet(anyString(), any())).thenReturn((RSet) idx);

        assertTrue(new PayloadLifecycleManager(client, nullPrefix).deletePayloadKey("streaming:mq:payload:t:p:0:k"));
        verify(idx).remove("streaming:mq:payload:t:p:0:k");
    }

    @Test
    void constructorFallsBackToControlPrefixForBlankKeyPrefix() throws Exception {
        MqOptions blankPrefix = new MqOptions() {
            @Override
            public String getKeyPrefix() {
                return "   ";
            }
        };
        when(bucket.delete()).thenReturn(true);
        RSet<String> idx = mock(RSet.class);
        when(client.getSet(anyString(), any())).thenReturn((RSet) idx);

        assertTrue(new PayloadLifecycleManager(client, blankPrefix).deletePayloadKey("streaming:mq:payload:t:p:0:k"));
        verify(idx).remove("streaming:mq:payload:t:p:0:k");
    }

    // ===== delete accounting =====

    @Test
    void deletePayloadKeyReportsBucketDeleteOutcome() throws Exception {
        when(bucket.delete()).thenReturn(false);
        assertFalse(new PayloadLifecycleManager(client).deletePayloadKey("streaming:mq:payload:t:p:0:k"));
        when(bucket.delete()).thenReturn(true);
        assertTrue(new PayloadLifecycleManager(client).deletePayloadKey("streaming:mq:payload:t:p:0:k"));
    }

    // ===== cleanup counters =====

    @Test
    void cleanupTopicCountsOnlyDeletedPayloads() throws Exception {
        RSet<String> idx = mock(RSet.class);
        when(client.getSet(anyString(), any())).thenReturn((RSet) idx);
        when(idx.toArray()).thenReturn(new Object[]{"k1", "k2"});
        when(bucket.delete()).thenReturn(false).thenReturn(true);

        long deleted = new PayloadLifecycleManager(client).cleanupTopicPayloadHashes("t");
        assertEquals(1, deleted);
    }

    @Test
    void cleanupOrphanedCountsOnlyDeletedPayloads() throws Exception {
        RScoredSortedSet<String> z = mock(RScoredSortedSet.class);
        when(client.getScoredSortedSet(anyString(), any())).thenReturn((RScoredSortedSet) z);
        when(z.valueRange(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.of("k1", "k2"));
        when(bucket.delete()).thenReturn(true).thenReturn(false);

        long deleted = new PayloadLifecycleManager(client).cleanupOrphanedPayloadHashes(0);
        assertEquals(1, deleted);
    }

    // ===== index cleanup failure handling =====

    @Test
    void removeFromIndicesSwallowsIndexFailures() throws Exception {
        RSet<String> idx = mock(RSet.class);
        RScoredSortedSet<String> z = mock(RScoredSortedSet.class);
        when(client.getSet(anyString(), any())).thenReturn((RSet) idx);
        when(client.getScoredSortedSet(anyString(), any())).thenReturn((RScoredSortedSet) z);
        doThrow(new IllegalStateException("idx boom")).when(idx).remove(anyString());
        doThrow(new IllegalStateException("zset boom")).when(z).remove(anyString());
        when(bucket.delete()).thenReturn(true);

        PayloadLifecycleManager plm = new PayloadLifecycleManager(client);
        assertTrue(plm.deletePayloadKey("streaming:mq:payload:t:p:0:k"));
        verify(idx).remove("streaming:mq:payload:t:p:0:k");
        verify(z).remove("streaming:mq:payload:t:p:0:k");
    }

    @Test
    void removeFromIndicesSkipsTopicIndexForForeignKeys() throws Exception {
        RSet<String> idx = mock(RSet.class);
        RScoredSortedSet<String> z = mock(RScoredSortedSet.class);
        when(client.getSet(anyString(), any())).thenReturn((RSet) idx);
        when(client.getScoredSortedSet(anyString(), any())).thenReturn((RScoredSortedSet) z);
        when(bucket.delete()).thenReturn(true);

        assertTrue(new PayloadLifecycleManager(client).deletePayloadKey("unrelated-key"));
        verify(idx, never()).remove(anyString());
        verify(z).remove("unrelated-key");
    }
}
