package io.github.cuihairu.redis.streaming.reliability.dlq;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked", "deprecation"})
class RedisDeadLetterServiceTest {

    @Test
    void sendAddsToDlqStream() {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<Object, Object> dlq = (RStream<Object, Object>) mock(RStream.class);
        when(redisson.getStream("stream:topic:t:dlq")).thenReturn((RStream) dlq);
        StreamMessageId mid = new StreamMessageId(1, 0);
        when(dlq.add(any(StreamAddArgs.class))).thenReturn(mid);

        RedisDeadLetterService service = new RedisDeadLetterService(redisson);
        DeadLetterRecord r = new DeadLetterRecord();
        r.originalTopic = "t";
        r.originalPartition = 2;
        r.payload = "p";
        StreamMessageId out = service.send(r);

        assertEquals(mid, out);
        verify(dlq).add(any(StreamAddArgs.class));
    }

    @Test
    void rangeReturnsEmptyOnException() {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<Object, Object> dlq = (RStream<Object, Object>) mock(RStream.class);
        when(redisson.getStream("stream:topic:t:dlq")).thenReturn((RStream) dlq);
        doThrow(new RuntimeException("boom")).when(dlq).range(anyInt(), any(StreamMessageId.class), any(StreamMessageId.class));

        RedisDeadLetterService service = new RedisDeadLetterService(redisson);
        assertTrue(service.range("t", 10).isEmpty());
    }

    @Test
    void sizeFallsBackToRangeWhenSizeIsZero() {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<Object, Object> dlq = (RStream<Object, Object>) mock(RStream.class);
        when(redisson.getStream("stream:topic:t:dlq")).thenReturn((RStream) dlq);
        when(dlq.size()).thenReturn(0L);

        StreamMessageId id = new StreamMessageId(10, 1);
        Map any = Map.of(id, Map.of("k", "v"));
        when(dlq.range(eq(1), eq(StreamMessageId.MIN), eq(StreamMessageId.MAX))).thenReturn(any);

        RedisDeadLetterService service = new RedisDeadLetterService(redisson);
        assertEquals(1L, service.size("t"));
    }

    @Test
    void deleteReturnsTrueWhenRemoved() {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<Object, Object> dlq = (RStream<Object, Object>) mock(RStream.class);
        when(redisson.getStream("stream:topic:t:dlq")).thenReturn((RStream) dlq);
        StreamMessageId id = new StreamMessageId(1, 1);
        when(dlq.remove(id)).thenReturn(1L);

        RedisDeadLetterService service = new RedisDeadLetterService(redisson);
        assertTrue(service.delete("t", id));
        verify(dlq).remove(id);
    }

    @Test
    void clearDeletesKeyAndReturnsPreviousSize() {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<Object, Object> dlq = (RStream<Object, Object>) mock(RStream.class);
        when(redisson.getStream("stream:topic:t:dlq")).thenReturn((RStream) dlq);
        when(dlq.size()).thenReturn(5L);

        RKeys keys = mock(RKeys.class);
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.delete("stream:topic:t:dlq")).thenReturn(1L);

        RedisDeadLetterService service = new RedisDeadLetterService(redisson);
        assertEquals(5L, service.clear("t"));
    }

    @Test
    void replayWithHandlerResolvesHashPayloadAndCallsPublish() {
        RedissonClient redisson = mock(RedissonClient.class);

        RStream<Object, Object> dlq = (RStream<Object, Object>) mock(RStream.class);
        when(redisson.getStream("stream:topic:t:dlq")).thenReturn((RStream) dlq);

        StreamMessageId id = new StreamMessageId(123, 0);
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", "2");
        data.put("payload", null);
        data.put("maxRetries", "7");
        data.put("headers", Map.of(
                "x-payload-storage-type", "hash",
                "x-payload-hash-ref", "ref1"
        ));

        Map msgs = Map.of(id, data);
        when(dlq.range(eq(1), eq(id), eq(id))).thenReturn(msgs);

        RBucket<String> bucket = (RBucket<String>) mock(RBucket.class);
        when(redisson.getBucket(eq("ref1"), eq(StringCodec.INSTANCE))).thenReturn((RBucket) bucket);
        when(bucket.get()).thenReturn("{\"foo\":\"bar\"}");

        ReplayHandler handler = mock(ReplayHandler.class);
        when(handler.publish(eq("t"), eq(2), any(), anyMap(), eq(7))).thenReturn(true);

        RedisDeadLetterService service = new RedisDeadLetterService(redisson, handler);
        assertTrue(service.replay("t", id));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(handler).publish(eq("t"), eq(2), payloadCaptor.capture(), headersCaptor.capture(), eq(7));
        assertTrue(payloadCaptor.getValue() instanceof Map);
        assertEquals("bar", ((Map<?, ?>) payloadCaptor.getValue()).get("foo"));
        assertEquals("hash", headersCaptor.getValue().get("x-payload-storage-type"));
        assertEquals("ref1", headersCaptor.getValue().get("x-payload-hash-ref"));
    }

    @Test
    void replayWithoutHandlerWritesToOriginalStreamAndExtendsPayloadTtl() {
        RedissonClient redisson = mock(RedissonClient.class);

        RStream<Object, Object> dlq = (RStream<Object, Object>) mock(RStream.class);
        when(redisson.getStream("stream:topic:t:dlq")).thenReturn((RStream) dlq);

        StreamMessageId id = new StreamMessageId(123, 0);
        Map<String, Object> data = new HashMap<>();
        data.put("partitionId", 1);
        data.put("payload", "p");
        data.put("maxRetries", 2);
        data.put("headers", Map.of(
                "x-payload-storage-type", "hash",
                "x-payload-hash-ref", "ref1"
        ));

        Map msgs = Map.of(id, data);
        when(dlq.range(eq(1), eq(id), eq(id))).thenReturn(msgs);

        RStream<Object, Object> orig = (RStream<Object, Object>) mock(RStream.class);
        when(redisson.getStream("stream:topic:t:p:1")).thenReturn((RStream) orig);
        when(orig.add(any(StreamAddArgs.class))).thenReturn(new StreamMessageId(999, 0));

        RBucket<String> bucket = (RBucket<String>) mock(RBucket.class);
        when(redisson.getBucket(eq("ref1"), eq(StringCodec.INSTANCE))).thenReturn((RBucket) bucket);
        when(bucket.get()).thenReturn("{\"payload\":\"v\"}");

        RedisDeadLetterService service = new RedisDeadLetterService(redisson);
        assertTrue(service.replay("t", id));

        verify(orig).add(any(StreamAddArgs.class));
        verify(bucket).set(eq("{\"payload\":\"v\"}"), eq(Duration.ofHours(24)));
    }

    @Test
    void replayReturnsFalseOnException() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getStream(anyString())).thenThrow(new RuntimeException("boom"));

        RedisDeadLetterService service = new RedisDeadLetterService(redisson);
        assertFalse(service.replay("t", new StreamMessageId(1, 1)));
    }
}
