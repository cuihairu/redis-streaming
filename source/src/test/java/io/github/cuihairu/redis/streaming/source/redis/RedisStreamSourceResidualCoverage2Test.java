package io.github.cuihairu.redis.streaming.source.redis;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the remaining {@link RedisStreamSource#run(StreamSource.SourceContext)} branches:
 * already-stopped contexts, null/empty batches, entries without field maps or value fields and
 * the mid-batch stop.
 */
@Timeout(30)
class RedisStreamSourceResidualCoverage2Test {

    @SuppressWarnings("unchecked")
    private static RStream<String, String> mockStream(RedissonClient redisson) {
        RStream<String, String> stream = mock(RStream.class);
        when(redisson.<String, String>getStream(eq("c100d-stream"), eq(StringCodec.INSTANCE))).thenReturn(stream);
        return stream;
    }

    private static StreamSource.SourceContext<String> ctx() {
        @SuppressWarnings("unchecked")
        StreamSource.SourceContext<String> ctx = mock(StreamSource.SourceContext.class);
        return ctx;
    }

    private static Map<StreamMessageId, Map<String, String>> batch(StreamMessageId id, Map<String, String> fields) {
        Map<StreamMessageId, Map<String, String>> batch = new LinkedHashMap<>();
        batch.put(id, fields);
        return batch;
    }

    @Test
    void runSkipsReadsWhenContextAlreadyStopped() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, String> stream = mockStream(redisson);
        RedisStreamSource<String> source = new RedisStreamSource<>(
                redisson, "c100d-stream", "grp", "consumer", "value", String.class, 5, 1L, 2);
        StreamSource.SourceContext<String> ctx = ctx();
        when(ctx.isStopped()).thenReturn(true);

        source.run(ctx);

        verify(stream, never()).readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class));
    }

    @Test
    void runToleratesNullAndEmptyBatches() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, String> stream = mockStream(redisson);
        @SuppressWarnings("unchecked")
        Map<StreamMessageId, Map<String, String>> empty = Map.of();
        when(stream.readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class))).thenReturn(null, empty);

        RedisStreamSource<String> source = new RedisStreamSource<>(
                redisson, "c100d-stream", "grp", "consumer", "value", String.class, 5, 1L, 2);
        StreamSource.SourceContext<String> ctx = ctx();
        when(ctx.isStopped()).thenReturn(false);

        source.run(ctx);

        verify(stream, never()).ack(anyString(), any());
    }

    @Test
    void runStopsMidBatchAfterContextStops() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, String> stream = mockStream(redisson);
        StreamMessageId first = new StreamMessageId(1, 0);
        StreamMessageId second = new StreamMessageId(2, 0);
        Map<StreamMessageId, Map<String, String>> batch = new LinkedHashMap<>();
        batch.put(first, Map.of("value", "a"));
        batch.put(second, Map.of("value", "b"));
        when(stream.readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class))).thenReturn(batch, Map.of());

        RedisStreamSource<String> source = new RedisStreamSource<>(
                redisson, "c100d-stream", "grp", "consumer", "value", String.class, 5, 1L, 2);
        StreamSource.SourceContext<String> ctx = ctx();
        // while-check, first entry check -> false; second entry check -> true
        when(ctx.isStopped()).thenReturn(false, false, true);

        source.run(ctx);

        verify(ctx).collectWithTimestamp(eq("a"), eq(1L));
        verify(ctx, never()).collectWithTimestamp(eq("b"), eq(2L));
        verify(stream).ack("grp", first);
        verify(stream, never()).ack("grp", second);
    }

    @Test
    void runAcksEntriesWithoutFieldMapOrValue() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, String> stream = mockStream(redisson);
        StreamMessageId nullFields = new StreamMessageId(1, 0);
        StreamMessageId missingValue = new StreamMessageId(2, 0);
        Map<StreamMessageId, Map<String, String>> batch = new LinkedHashMap<>();
        batch.put(nullFields, null);
        batch.put(missingValue, Map.of("other", "x"));
        when(stream.readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class))).thenReturn(batch, Map.of());

        RedisStreamSource<String> source = new RedisStreamSource<>(
                redisson, "c100d-stream", "grp", "consumer", "value", String.class, 5, 1L, 2);
        StreamSource.SourceContext<String> ctx = ctx();
        when(ctx.isStopped()).thenReturn(false);

        source.run(ctx);

        verify(ctx, never()).collectWithTimestamp(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(stream).ack("grp", nullFields);
        verify(stream).ack("grp", missingValue);
    }

    @Test
    void runCollectsValuesAndAcksThem() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, String> stream = mockStream(redisson);
        StreamMessageId id = new StreamMessageId(7, 0);
        when(stream.readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class)))
                .thenReturn(batch(id, Map.of("value", "hello")), Map.of());

        RedisStreamSource<String> source = new RedisStreamSource<>(
                redisson, "c100d-stream", "grp", "consumer", "value", String.class, 5, 1L, 2);
        StreamSource.SourceContext<String> ctx = ctx();
        when(ctx.isStopped()).thenReturn(false);

        source.run(ctx);

        verify(ctx).collectWithTimestamp(eq("hello"), eq(7L));
        verify(stream).ack("grp", id);
        assertEquals("c100d-stream", source.getStreamName());
    }
}
