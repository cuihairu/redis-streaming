package io.github.cuihairu.redis.streaming.sink.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisListSinkTest {

    @Test
    void writeStoresStringValueDirectly() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.add("v")).thenReturn(true);

        RedisListSink<String> sink = new RedisListSink<>(redisson, "l");
        assertTrue(sink.write("v"));
        verify(list).add("v");
        assertEquals("l", sink.getListName());
    }

    @Test
    void invokeWritesElement() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);

        RedisListSink<String> sink = new RedisListSink<>(redisson, "l");
        sink.invoke("x");
        verify(list).add("x");
    }

    @Test
    void writeSerializesNonString() {
        record Event(int x) {}

        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.add(argThat(s -> s.contains("\"x\":1")))).thenReturn(true);

        RedisListSink<Event> sink = new RedisListSink<>(redisson, "l", new ObjectMapper());
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

        RedisListSink<String> sink = new RedisListSink<>(redisson, "l");
        assertEquals(2, sink.writeBatch(List.of("a", "b", "c")));
    }

    @Test
    void sizeClearAndDeleteDelegateToList() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.size()).thenReturn(7);

        RedisListSink<String> sink = new RedisListSink<>(redisson, "l");
        assertEquals(7, sink.getSize());

        sink.clear();
        verify(list).clear();

        sink.deleteList();
        verify(list).delete();
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new RedisListSink<>(null, "l"));
        assertThrows(NullPointerException.class, () -> new RedisListSink<>(mock(RedissonClient.class), null));
        assertThrows(NullPointerException.class, () -> new RedisListSink<>(mock(RedissonClient.class), "l", null));
    }
}
