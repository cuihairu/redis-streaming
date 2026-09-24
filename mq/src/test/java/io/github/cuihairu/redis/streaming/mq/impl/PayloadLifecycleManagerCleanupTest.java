package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers PayloadLifecycleManager.storePayload TTL branch, deletePayloadKey,
 * removeFromIndices/parseTopicFromPayloadKey and cleanupTopicPayloadHashes.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class PayloadLifecycleManagerCleanupTest {

    private RedissonClient client;
    private RBucket<String> bucket;
    private RSet<String> topicIndex;
    private RScoredSortedSet<String> timeIndex;

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        topicIndex = mock(RSet.class);
        timeIndex = mock(RScoredSortedSet.class);
        when(client.getBucket(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) bucket);
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn((RSet) topicIndex);
        when(client.getScoredSortedSet(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RScoredSortedSet) timeIndex);
    }

    @Test
    void storeLargePayloadWithTtlSetsExpiryAndIndexes() {
        PayloadLifecycleManager plm = new PayloadLifecycleManager(client,
                MqOptions.builder().keyPrefix("pfx").retentionMs(60_000).build());
        String key = plm.storeLargePayload("tp", 0, "payload");
        assertTrue(key.startsWith("pfx:payload:tp:p:0:"), key);
        verify(bucket).set(eq("\"payload\""), eq(Duration.ofMillis(60_000)));
        verify(topicIndex).add(key);
        verify(timeIndex).add(any(Double.class), eq(key));
    }

    @Test
    void storeLargePayloadWrapsFailures() {
        PayloadLifecycleManager plm = new PayloadLifecycleManager(client, MqOptions.builder().build());
        doThrow(new IllegalStateException("down")).when(bucket).set(anyString());
        RuntimeException e = assertThrows(RuntimeException.class, () -> plm.storeLargePayload("tp", 0, "p"));
        assertTrue(e.getMessage().contains("Failed to store payload"));
    }

    @Test
    void deletePayloadKeyHandlesNullEmptyAndRealKeys() {
        PayloadLifecycleManager plm = new PayloadLifecycleManager(client, MqOptions.builder().keyPrefix("pfx").build());
        assertFalse(plm.deletePayloadKey(null));
        assertFalse(plm.deletePayloadKey(""));
        when(bucket.delete()).thenReturn(true);

        // well-formed key removes from both indices (parseTopicFromPayloadKey happy path)
        assertTrue(plm.deletePayloadKey("pfx:payload:tp:p:0:uuid"));
        verify(topicIndex).remove("pfx:payload:tp:p:0:uuid");
        verify(timeIndex).remove("pfx:payload:tp:p:0:uuid");

        // key without ":p:" marker: topic unparseable, still removed from global index
        assertTrue(plm.deletePayloadKey("pfx:payload:weird"));
        verify(topicIndex, times(1)).remove(anyString());
        verify(timeIndex, times(2)).remove(anyString());

        // key with wrong prefix: no topic index touch
        assertTrue(plm.deletePayloadKey("other:payload:tp:p:0:u2"));
        verify(topicIndex, times(1)).remove(anyString());
        verify(timeIndex, times(3)).remove(anyString());
    }

    @Test
    void cleanupTopicPayloadHashesDeletesIndexedKeys() {
        PayloadLifecycleManager plm = new PayloadLifecycleManager(client, MqOptions.builder().keyPrefix("pfx").build());
        // new ArrayList<>(idx) copies via Collection#toArray
        when(topicIndex.toArray()).thenReturn(new Object[]{"k1", "k2"});
        when(bucket.delete()).thenReturn(true);

        assertEquals(2, plm.cleanupTopicPayloadHashes("tp"));
    }

    @Test
    void cleanupTopicPayloadHashesSwallowsFailures() {
        PayloadLifecycleManager plm = new PayloadLifecycleManager(client, MqOptions.builder().build());
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenThrow(new IllegalStateException("down"));
        assertEquals(0, plm.cleanupTopicPayloadHashes("tp"));
    }

    @Test
    void cleanupOrphanedPayloadHashesRemovesOldEntries() {
        PayloadLifecycleManager plm = new PayloadLifecycleManager(client, MqOptions.builder().keyPrefix("pfx").build());
        RScoredSortedSet<String> z = mock(RScoredSortedSet.class);
        when(client.getScoredSortedSet(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RScoredSortedSet) z);
        when(z.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("old-1", "old-2"));
        when(bucket.delete()).thenReturn(true);

        assertEquals(2, plm.cleanupOrphanedPayloadHashes(60_000));

        when(z.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenThrow(new IllegalStateException("zset gone"));
        assertEquals(0, plm.cleanupOrphanedPayloadHashes(0));
    }

    @Test
    void loadPayloadMissingThrowsPayloadNotFound() {
        PayloadLifecycleManager plm = new PayloadLifecycleManager(client, MqOptions.builder().build());
        when(bucket.get()).thenReturn(null);
        RuntimeException e = assertThrows(RuntimeException.class, () -> plm.loadPayload("gone"));
        assertTrue(e.getMessage().contains("Failed to load payload"));
    }
}
