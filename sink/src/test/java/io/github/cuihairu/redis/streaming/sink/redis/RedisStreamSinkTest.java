package io.github.cuihairu.redis.streaming.sink.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.client.codec.StringCodec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamSinkTest {

    @SuppressWarnings("unchecked")
    private final RStream<String, String> stream = mock(RStream.class);

    private RedissonClient redissonWithStream(String name) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String, String>getStream(name, StringCodec.INSTANCE)).thenReturn(stream);
        return redisson;
    }

    @Test
    void writeAppendsEntryViaXadd() {
        RedissonClient redisson = redissonWithStream("s");
        when(stream.add(any(StreamAddArgs.class))).thenReturn(new StreamMessageId(1, 0));

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "s");
        assertTrue(sink.write("v"));
        verify(stream).add(any(StreamAddArgs.class));
        assertEquals("s", sink.getStreamName());
        assertEquals(RedisStreamSink.DEFAULT_VALUE_FIELD, sink.getValueField());
    }

    @Test
    void writeSerializesNonStringWithCustomField() {
        record Event(int x) {}

        RedissonClient redisson = redissonWithStream("s");
        when(stream.add(any(StreamAddArgs.class))).thenReturn(new StreamMessageId(1, 0));

        RedisStreamSink<Event> sink = new RedisStreamSink<>(redisson, "s", "payload", new ObjectMapper());
        assertTrue(sink.write(new Event(1)));
        assertEquals("payload", sink.getValueField());
        verify(stream).add(any(StreamAddArgs.class));
    }

    @Test
    void invokeWritesElement() throws Exception {
        RedissonClient redisson = redissonWithStream("s");
        when(stream.add(any(StreamAddArgs.class))).thenReturn(new StreamMessageId(1, 0));

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "s");
        sink.invoke("x");
        verify(stream).add(any(StreamAddArgs.class));
    }

    @Test
    void writeBatchIssuesOneXaddPerElement() {
        RedissonClient redisson = redissonWithStream("s");
        when(stream.add(any(StreamAddArgs.class))).thenReturn(new StreamMessageId(1, 0));

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "s");
        assertEquals(3, sink.writeBatch(List.of("a", "b", "c")));
        verify(stream, times(3)).add(any(StreamAddArgs.class));
    }

    @Test
    void sizeAndClearDelegateToStream() {
        RedissonClient redisson = redissonWithStream("s");
        when(stream.size()).thenReturn(7L);

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "s");
        assertEquals(7L, sink.getSize());

        sink.clear();
        verify(stream).delete();
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new RedisStreamSink<>(null, "s"));
        assertThrows(NullPointerException.class, () -> new RedisStreamSink<>(mock(RedissonClient.class), null));
    }
}
