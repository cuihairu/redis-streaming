package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link RedisDeadLetterService}: size fallbacks, replay header
 * normalization (map/string forms with null entries), hash payload re-store combinations and
 * defensive failure handling.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisDeadLetterServiceSprintCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> dlq;
    private RStream<String, Object> orig;
    private RBucket<String> bucket;
    private ReplayHandler handler;

    private static final StreamMessageId ID = new StreamMessageId(4, 0);
    private static final String DLQ_KEY = "stream:topic:t:dlq";
    private static final String ORIG_KEY = "stream:topic:t:p:0";

    @BeforeEach
    void setUp() throws Exception {
        client = mock(RedissonClient.class);
        dlq = mock(RStream.class);
        orig = mock(RStream.class);
        bucket = mock(RBucket.class);
        handler = mock(ReplayHandler.class);
        when(client.getStream(DLQ_KEY)).thenReturn((RStream) dlq);
        when(client.getStream(ORIG_KEY)).thenReturn((RStream) orig);
        when(client.getBucket(any(), any())).thenReturn((RBucket) bucket);
    }

    private RedisDeadLetterService serviceWithHandler() {
        return new RedisDeadLetterService(client, handler);
    }

    private RedisDeadLetterService serviceWithoutHandler() {
        return new RedisDeadLetterService(client);
    }

    private static Map<StreamMessageId, Map<String, Object>> one(Map<String, Object> data) {
        Map<StreamMessageId, Map<String, Object>> out = new LinkedHashMap<>();
        out.put(ID, data);
        return out;
    }

    // ===== size =====

    @Test
    void sizeFallsBackToRangeReturningNull() throws Exception {
        when(dlq.size()).thenReturn(0L);
        when(dlq.range(1, StreamMessageId.MIN, StreamMessageId.MAX)).thenReturn(null);
        assertEquals(0, serviceWithHandler().size("t"));
    }

    // ===== replay with handler: map headers with null entries =====

    @Test
    void replayWithHandlerDropsNullHeaderEntries() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        Map<String, Object> headers = new HashMap<>();
        headers.put(null, "orphan");
        headers.put("k", null);
        headers.put("a", "b");
        data.put("headers", headers);
        when(dlq.range(1, ID, ID)).thenReturn(one(data));
        when(handler.publish(eq("t"), eq(0), any(), any(), eq(3))).thenReturn(true);

        assertTrue(serviceWithHandler().replay("t", ID));

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(handler).publish(eq("t"), eq(0), any(), captor.capture(), eq(3));
        Map<String, String> passed = (Map<String, String>) captor.getValue();
        assertEquals(1, passed.size());
        assertEquals("b", passed.get("a"));
    }

    // ===== replay with handler: string headers variants =====

    @Test
    void replayWithStringHeadersNullJsonAndNullValues() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", "0");
        data.put("headers", "null");
        data.put("maxRetries", "not-a-number");
        when(dlq.range(1, ID, ID)).thenReturn(one(data));
        when(handler.publish(eq("t"), eq(0), any(), any(), eq(3))).thenReturn(true);

        assertTrue(serviceWithHandler().replay("t", ID));

        Map<String, Object> data2 = new HashMap<>();
        data2.put("partitionId", "0");
        data2.put("headers", "{\"a\":\"b\",\"z\":null}");
        when(dlq.range(1, ID, ID)).thenReturn(one(data2));

        assertTrue(serviceWithHandler().replay("t", ID));
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(handler, org.mockito.Mockito.times(2)).publish(eq("t"), eq(0), any(), captor.capture(), eq(3));
        Map<String, String> passed = (Map<String, String>) captor.getAllValues().get(1);
        assertEquals("b", passed.get("a"));
        assertFalse(passed.containsKey("z"));
    }

    // ===== replay with handler: hash payload resolution combinations =====

    @Test
    void replaySkipsHashBlockWhenInlinePayloadPresent() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        data.put("payload", "inline");
        Map<String, String> headers = new HashMap<>();
        headers.put("x-payload-storage-type", "hash");
        headers.put("x-payload-hash-ref", "ref-1");
        data.put("headers", headers);
        when(dlq.range(1, ID, ID)).thenReturn(one(data));
        when(handler.publish(eq("t"), eq(0), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);

        assertTrue(serviceWithHandler().replay("t", ID));
        verify(client, never()).getBucket(eq("ref-1"), any());
    }

    @Test
    void replaySkipsHashBlockWithoutStorageTypeOrRef() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-payload-hash-ref", "");
        data.put("headers", headers);
        when(dlq.range(1, ID, ID)).thenReturn(one(data));
        when(handler.publish(eq("t"), eq(0), any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);

        assertTrue(serviceWithHandler().replay("t", ID));
        verify(client, never()).getBucket(eq(""), any());

        Map<String, Object> data2 = new HashMap<>();
        data2.put("partitionId", 0);
        Map<String, String> headers2 = new HashMap<>();
        headers2.put("x-payload-storage-type", "hash");
        data2.put("headers", headers2);
        when(dlq.range(1, ID, ID)).thenReturn(one(data2));

        assertTrue(serviceWithHandler().replay("t", ID));
    }

    // ===== replay without handler: map headers with null entries =====

    @Test
    void replayWithoutHandlerDropsNullHeaderEntries() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        Map<String, Object> headers = new HashMap<>();
        headers.put(null, "orphan");
        headers.put("k", null);
        headers.put("a", "b");
        data.put("headers", headers);
        when(dlq.range(1, ID, ID)).thenReturn(one(data));
        when(orig.add(any())).thenReturn(new StreamMessageId(9, 0));

        assertTrue(serviceWithoutHandler().replay("t", ID));
        verify(orig).add(any());
    }

    // ===== replay without handler: string header parse failures and hash combos =====

    @Test
    void replayWithoutHandlerToleratesBrokenStringHeaders() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        data.put("headers", "{broken json");
        when(dlq.range(1, ID, ID)).thenReturn(one(data));
        when(orig.add(any())).thenReturn(new StreamMessageId(9, 0));

        assertTrue(serviceWithoutHandler().replay("t", ID));
        verify(orig).add(any());
    }

    @Test
    void replayWithoutHandlerHashBlockCombinations() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-payload-storage-type", "inline");
        headers.put("x-payload-hash-ref", "ref-x");
        data.put("headers", headers);
        when(dlq.range(1, ID, ID)).thenReturn(one(data));
        when(orig.add(any())).thenReturn(new StreamMessageId(9, 0));

        assertTrue(serviceWithoutHandler().replay("t", ID));
        verify(client, never()).getBucket(eq("ref-x"), any());

        Map<String, Object> data2 = new HashMap<>();
        data2.put("partitionId", 0);
        Map<String, String> headers2 = new HashMap<>();
        headers2.put("x-payload-storage-type", "hash");
        data2.put("headers", headers2);
        when(dlq.range(1, ID, ID)).thenReturn(one(data2));

        assertTrue(serviceWithoutHandler().replay("t", ID));

        Map<String, Object> data3 = new HashMap<>();
        data3.put("partitionId", 0);
        Map<String, String> headers3 = new HashMap<>();
        headers3.put("x-payload-storage-type", "hash");
        headers3.put("x-payload-hash-ref", "");
        data3.put("headers", headers3);
        when(dlq.range(1, ID, ID)).thenReturn(one(data3));

        assertTrue(serviceWithoutHandler().replay("t", ID));
    }

    @Test
    void replayWithoutHandlerMissingHashPayloadCopiesHeadersWithNullEntries() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        Map<Object, Object> headers = new HashMap<>();
        headers.put(null, "orphan");
        headers.put("k", null);
        headers.put("a", "b");
        headers.put("x-payload-storage-type", "hash");
        headers.put("x-payload-hash-ref", "ref-m");
        data.put("headers", headers);
        when(dlq.range(1, ID, ID)).thenReturn(one(data));
        when(bucket.get()).thenReturn(null);
        when(orig.add(any())).thenReturn(new StreamMessageId(9, 0));

        assertTrue(serviceWithoutHandler().replay("t", ID));
        verify(orig).add(any());
    }

    // ===== replay without handler: failure inside the header normalization block =====

    @Test
    void replayWithoutHandlerSwallowsHashBucketFailure() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-payload-storage-type", "hash");
        headers.put("x-payload-hash-ref", "ref-e");
        data.put("headers", headers);
        when(dlq.range(1, ID, ID)).thenReturn(one(data));
        when(client.getBucket("ref-e", StringCodec.INSTANCE)).thenThrow(new IllegalStateException("bucket boom"));
        when(orig.add(any())).thenReturn(new StreamMessageId(9, 0));

        assertTrue(serviceWithoutHandler().replay("t", ID));
        verify(orig).add(any());
    }
}
