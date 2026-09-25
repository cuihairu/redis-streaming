package io.github.cuihairu.redis.streaming.sink.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the false outcome of {@link RedisStreamSink#writeBatch(Iterable)}: entries whose XADD
 * reports no id are not counted as written.
 */
class RedisStreamSinkWriteBatchResidualCoverageTest {

    @SuppressWarnings("unchecked")
    @Test
    void writeBatchCountsOnlyEntriesWithAssignedIds() {
        RedissonClient redisson = mock(RedissonClient.class);
        RStream<String, String> stream = mock(RStream.class);
        when(redisson.<String, String>getStream(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(org.redisson.client.codec.StringCodec.INSTANCE))).thenReturn(stream);
        when(stream.add(any(org.redisson.api.stream.StreamAddArgs.class)))
                .thenReturn(null, new StreamMessageId(1, 0));

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "c100d-stream");

        assertEquals(1, sink.writeBatch(List.of("a", "b")));
    }

    @SuppressWarnings("unchecked")
    @Test
    void writeBatchOfEmptyIterableWritesNothing() {
        RedissonClient redisson = mock(RedissonClient.class);
        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "c100d-stream");

        assertEquals(0, sink.writeBatch(List.of()));
        verify(redisson, never()).getStream(any(String.class), any(org.redisson.client.codec.Codec.class));
    }
}
