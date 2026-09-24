package io.github.cuihairu.redis.streaming.mq;

import io.github.cuihairu.redis.streaming.mq.impl.PayloadHeaders;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers DeadLetterQueueManager.replayMessage paths (inline, hash re-store, codec
 * fallbacks, parse failures) and the getDeadLetterMessages outer fallback.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class DeadLetterQueueManagerReplayBranchesTest {

    private RedissonClient client;
    private RStream<String, Object> dlqDefault;
    private RStream<String, Object> dlqString;
    private RStream<String, Object> partition;
    private RBucket<String> bucket;
    private DeadLetterQueueManager manager;

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        dlqDefault = mock(RStream.class);
        dlqString = mock(RStream.class);
        partition = mock(RStream.class);
        bucket = mock(RBucket.class);
        manager = new DeadLetterQueueManager(client);
    }

    private void stubDefaultDlq(StreamMessageId id, Map<String, Object> data) {
        Map<StreamMessageId, Map<String, Object>> one = new LinkedHashMap<>();
        one.put(id, data);
        when(client.getStream(anyString())).thenReturn((RStream) dlqDefault);
        when(dlqDefault.range(eq(1), eq(id), eq(id))).thenReturn(one);
    }

    @Test
    void replayInlinePayloadRestoresToPartitionStream() {
        StreamMessageId id = new StreamMessageId(1, 0);
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "inline-p");
        data.put("partitionId", 1);
        data.put("key", "k");
        data.put("maxRetries", "5");
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_INLINE);
        data.put("headers", headers);
        stubDefaultDlq(id, data);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 1)), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RStream) partition);
        when(partition.add(any())).thenReturn(id);

        assertTrue(manager.replayMessage("t", id));
        verify(partition).add(any());
    }

    @Test
    void replayHashPayloadReStoresWithFreshKey() {
        StreamMessageId id = new StreamMessageId(2, 0);
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "");
        data.put("partitionId", 0);
        data.put("maxRetries", 3);
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "old-ref");
        data.put("headers", headers);
        stubDefaultDlq(id, data);

        when(client.getBucket(eq("old-ref"), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RBucket) bucket);
        when(bucket.get()).thenReturn("{\"a\":1}");
        when(client.getBucket(argThat((String k) -> k != null && !k.equals("old-ref")),
                any(org.redisson.client.codec.Codec.class))).thenReturn((RBucket) mock(RBucket.class));
        when(client.getSet(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RSet) mock(RSet.class));
        when(client.getScoredSortedSet(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((org.redisson.api.RScoredSortedSet) mock(org.redisson.api.RScoredSortedSet.class));
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RStream) partition);
        when(partition.add(any())).thenReturn(id);

        assertTrue(manager.replayMessage("t", id));
        verify(partition).add(any());
    }

    @Test
    void replayHashWithoutRefFallsBackToInline() {
        StreamMessageId id = new StreamMessageId(3, 0);
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "still-here");
        data.put("partitionId", 0);
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        data.put("headers", headers);
        stubDefaultDlq(id, data);
        when(client.getStream(eq(StreamKeys.partitionStream("t", 0)), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RStream) partition);
        when(partition.add(any())).thenReturn(id);

        assertTrue(manager.replayMessage("t", id));
    }

    @Test
    void replayFallsBackToStringCodecAndStringHeaders() {
        StreamMessageId id = new StreamMessageId(4, 0);
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "p");
        data.put("partitionId", "2"); // string form
        data.put("maxRetries", "not-a-number");
        data.put("headers", "{\"a\":\"b\"}"); // JSON string headers
        Map<StreamMessageId, Map<String, Object>> one = new LinkedHashMap<>();
        one.put(id, data);
        when(client.getStream(anyString())).thenReturn((RStream) dlqDefault);
        when(dlqDefault.range(eq(1), eq(id), eq(id))).thenReturn(new LinkedHashMap<>());
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class))).thenAnswer(inv -> {
            if (inv.getArgument(1) instanceof org.redisson.client.codec.StringCodec
                    && StreamKeys.partitionStream("t", 2).equals(inv.getArgument(0))) {
                return partition;
            }
            return dlqString;
        });
        when(dlqString.range(eq(1), eq(id), eq(id))).thenReturn(one);
        when(partition.add(any())).thenReturn(id);

        assertTrue(manager.replayMessage("t", id));
    }

    @Test
    void replayMissingEntryAndFailuresReturnFalse() {
        StreamMessageId id = new StreamMessageId(5, 0);
        when(client.getStream(anyString())).thenReturn((RStream) dlqDefault);
        when(dlqDefault.range(eq(1), eq(id), eq(id))).thenReturn(new LinkedHashMap<>());
        when(client.getStream(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RStream) dlqString);
        when(dlqString.range(eq(1), eq(id), eq(id))).thenReturn(new LinkedHashMap<>());
        assertFalse(manager.replayMessage("t", id));

        // hash payload whose old ref cannot be loaded -> outer catch -> false
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "");
        data.put("partitionId", 0);
        Map<String, String> headers = new HashMap<>();
        headers.put(PayloadHeaders.PAYLOAD_STORAGE_TYPE, PayloadHeaders.STORAGE_TYPE_HASH);
        headers.put(PayloadHeaders.PAYLOAD_HASH_REF, "broken-ref");
        data.put("headers", headers);
        stubDefaultDlq(id, data);
        when(client.getBucket(eq("broken-ref"), any(org.redisson.client.codec.Codec.class)))
                .thenThrow(new IllegalStateException("bucket gone"));
        assertFalse(manager.replayMessage("t", id));
    }

    @Test
    void getDeadLetterMessagesOuterFailureReturnsEmpty() {
        when(client.getStream(anyString())).thenThrow(new IllegalStateException("down"));
        assertTrue(manager.getDeadLetterMessages("t", 5).isEmpty());
    }
}
