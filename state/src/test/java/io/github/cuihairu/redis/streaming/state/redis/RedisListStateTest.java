package io.github.cuihairu.redis.streaming.state.redis;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisListStateTest {

    @Test
    void delegatesAddClearAndGet() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("k")).thenReturn(list);
        when(list.toArray()).thenReturn(new Object[]{"a", "b"});

        RedisListState<String> state = new RedisListState<>(redisson, "k", String.class);

        state.add("x");
        verify(list).add("x");

        assertEquals(List.of("a", "b"), state.get());

        state.clear();
        verify(list).clear();
        assertEquals("k", state.getKey());
    }

    @Test
    void updateClearsThenAddsAllElements() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("k")).thenReturn(list);

        RedisListState<String> state = new RedisListState<>(redisson, "k", String.class);
        state.update(List.of("a", "b", "c"));

        InOrder inOrder = inOrder(list);
        inOrder.verify(list).clear();
        inOrder.verify(list).add("a");
        inOrder.verify(list).add("b");
        inOrder.verify(list).add("c");
    }

    @Test
    void addAllAddsEachElement() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("k")).thenReturn(list);

        RedisListState<String> state = new RedisListState<>(redisson, "k", String.class);
        state.addAll(List.of("a", "b"));

        verify(list).add("a");
        verify(list).add("b");
    }
}
