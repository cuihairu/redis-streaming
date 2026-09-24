package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers RedisDeadLetterService.replay() header-as-json-string branches (synthetic
 * TypeReference classes and forEach lambdas) for both replay-handler and fallback paths.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisDeadLetterServiceReplayHeadersTest {

    private RedissonClient client;
    private RStream<String, Object> stream;

    @BeforeEach
    void setUp() {
        client = mock(RedissonClient.class);
        stream = mock(RStream.class);
        when(client.getStream(anyString())).thenReturn((RStream) stream);
        when(stream.add(any())).thenReturn(new StreamMessageId(1, 1));
    }

    @Test
    void replayWithHandlerParsesJsonStringHeaders() {
        ReplayHandler handler = mock(ReplayHandler.class);
        RedisDeadLetterService service = new RedisDeadLetterService(client, handler);
        StreamMessageId id = new StreamMessageId(5, 0);

        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("maxRetries", "4");
        data.put("headers", "{\"k\":\"v\",\"num\":7}");
        when(stream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(handler.publish(eq("t"), eq(1), eq("p"), any(), eq(4))).thenReturn(true);

        assertTrue(service.replay("t", id));
        verify(handler).publish(eq("t"), eq(1), eq("p"), any(), eq(4));
    }

    @Test
    void replayWithHandlerParsesJsonStringHeadersWithHashPayloadMissing() {
        ReplayHandler handler = mock(ReplayHandler.class);
        RedisDeadLetterService service = new RedisDeadLetterService(client, handler);
        StreamMessageId id = new StreamMessageId(5, 1);

        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        data.put("payload", "");
        data.put("headers", "{\"x-payload-storage-type\":\"hash\",\"x-payload-hash-ref\":\"ref-1\"}");
        when(stream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(handler.publish(eq("t"), eq(0), any(), any(), eq(3))).thenReturn(true);

        assertTrue(service.replay("t", id));
    }

    @Test
    void replayWithoutHandlerParsesJsonStringHeaders() {
        RedisDeadLetterService service = new RedisDeadLetterService(client);
        StreamMessageId id = new StreamMessageId(5, 2);

        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 2);
        data.put("payload", "p");
        data.put("headers", "{\"a\":\"b\"}");
        when(stream.range(1, id, id)).thenReturn(Map.of(id, data));

        assertTrue(service.replay("t", id));
        verify(stream).add(any());
    }

    @Test
    void replayWithoutHandlerJsonStringHeadersWithHashPayloadRefreshesTtl() {
        RedisDeadLetterService service = new RedisDeadLetterService(client);
        StreamMessageId id = new StreamMessageId(5, 3);
        org.redisson.api.RBucket<String> bucket = mock(org.redisson.api.RBucket.class);
        when(client.getBucket(eq("ref-2"), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((org.redisson.api.RBucket) bucket);
        when(bucket.get()).thenReturn("\"loaded\"");

        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        data.put("payload", "");
        data.put("headers", "{\"x-payload-storage-type\":\"hash\",\"x-payload-hash-ref\":\"ref-2\"}");
        when(stream.range(1, id, id)).thenReturn(Map.of(id, data));

        assertTrue(service.replay("t", id));
        verify(bucket).set(eq("\"loaded\""), any(java.time.Duration.class));
    }

    @Test
    void replayWithoutHandlerJsonStringHeadersMissingPayload() {
        RedisDeadLetterService service = new RedisDeadLetterService(client);
        StreamMessageId id = new StreamMessageId(5, 4);
        org.redisson.api.RBucket<String> bucket = mock(org.redisson.api.RBucket.class);
        when(client.getBucket(eq("ref-3"), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((org.redisson.api.RBucket) bucket);
        when(bucket.get()).thenReturn(null);

        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        data.put("payload", "");
        data.put("headers", "{\"x-payload-storage-type\":\"hash\",\"x-payload-hash-ref\":\"ref-3\"}");
        when(stream.range(1, id, id)).thenReturn(Map.of(id, data));

        assertTrue(service.replay("t", id));
        verify(stream).add(any());
    }

    @Test
    void replayWithBrokenJsonStringHeadersStillPublishes() {
        ReplayHandler handler = mock(ReplayHandler.class);
        RedisDeadLetterService service = new RedisDeadLetterService(client, handler);
        StreamMessageId id = new StreamMessageId(5, 5);

        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 0);
        data.put("payload", "p");
        data.put("headers", "{broken-json");
        when(stream.range(1, id, id)).thenReturn(Map.of(id, data));
        when(handler.publish(eq("t"), anyInt(), any(), any(), anyInt())).thenReturn(false);

        assertFalse(service.replay("t", id));
    }
}
