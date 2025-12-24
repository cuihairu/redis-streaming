package io.github.cuihairu.redis.streaming.source.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisListSourceTest {

    @Test
    void readOneReturnsNullOnEmptyList() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.remove(0)).thenThrow(new IndexOutOfBoundsException());

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            assertNull(source.readOne());
        } finally {
            source.close();
        }
    }

    @Test
    void readOneDeserializesStringWithoutJson() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.remove(0)).thenReturn("v");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            assertEquals("v", source.readOne());
        } finally {
            source.close();
        }
    }

    @Test
    void readBatchStopsWhenListBecomesEmpty() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);

        when(list.isEmpty()).thenReturn(false, false, true);
        when(list.remove(0)).thenReturn("a", "b");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            assertEquals(List.of("a", "b"), source.readBatch(10));
        } finally {
            source.close();
        }
    }

    @Test
    void readAllReadsUntilEmpty() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);

        when(list.isEmpty()).thenReturn(false, false, true);
        when(list.remove(0)).thenReturn("a", "b");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            assertEquals(List.of("a", "b"), source.readAll());
        } finally {
            source.close();
        }
    }

    @Test
    void deserializeInvalidJsonDropsElement() {
        record Event(int x) {}

        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(false, true);
        when(list.remove(0)).thenReturn("not-json");

        RedisListSource<Event> source = new RedisListSource<>(redisson, "l", new ObjectMapper(), Event.class);
        try {
            assertTrue(source.readAll().isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    void pollBatchRejectsNonPositiveBatchSize() {
        RedisListSource<String> source = new RedisListSource<>(mock(RedissonClient.class), "l", String.class);
        try {
            assertThrows(IllegalArgumentException.class, () ->
                    source.pollBatch(batch -> {
                    }, 0, Duration.ofMillis(10)));
        } finally {
            source.close();
        }
    }
}

