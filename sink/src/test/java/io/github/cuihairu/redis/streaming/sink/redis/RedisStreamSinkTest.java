package io.github.cuihairu.redis.streaming.sink.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamSinkTest {

    @Test
    void writeStoresStringValueDirectly() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.add("v")).thenReturn(true);

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "l", new ObjectMapper());
        assertTrue(sink.write("v"));
        verify(list).add("v");
        assertEquals("l", sink.getStreamName());
    }

    @Test
    void writeSerializesNonString() {
        record Event(int x) {}

        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.add(argThat(s -> s.contains("\"x\":1")))).thenReturn(true);

        RedisStreamSink<Event> sink = new RedisStreamSink<>(redisson, "l", new ObjectMapper());
        assertTrue(sink.write(new Event(1)));
        verify(list).add(argThat(s -> s.contains("\"x\":1")));
    }

    @Test
    void writeBatchCountsSuccessfulWrites() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.add("a")).thenReturn(true);
        when(list.add("b")).thenReturn(false);
        when(list.add("c")).thenReturn(true);

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "l");
        assertEquals(2, sink.writeBatch(List.of("a", "b", "c")));
    }

    @Test
    void sizeClearAndDeleteDelegateToList() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.size()).thenReturn(7);

        RedisStreamSink<String> sink = new RedisStreamSink<>(redisson, "l");
        assertEquals(7, sink.getSize());

        sink.clear();
        verify(list).clear();

        sink.deleteList();
        verify(list).delete();
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new RedisStreamSink<>(null, "l"));
        assertThrows(NullPointerException.class, () -> new RedisStreamSink<>(mock(RedissonClient.class), null));
        assertThrows(NullPointerException.class, () -> new RedisStreamSink<>(mock(RedissonClient.class), "l", null));
    }
}

