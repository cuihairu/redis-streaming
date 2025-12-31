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

    @Test
    void readBatchReturnsEmptyListWhenInitiallyEmpty() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(true);

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            assertTrue(source.readBatch(10).isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    void readBatchRespectsBatchSize() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(false, false, false, true);
        when(list.remove(0)).thenReturn("a", "b", "c", "d", "e");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            List<String> batch = source.readBatch(3);
            assertEquals(3, batch.size());
            assertEquals(List.of("a", "b", "c"), batch);
        } finally {
            source.close();
        }
    }

    @Test
    void readOneReturnsSingleElement() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.remove(0)).thenReturn("single-element");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            assertEquals("single-element", source.readOne());
        } finally {
            source.close();
        }
    }

    @Test
    void readBatchWithBatchSizeOfOne() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(false, true);
        when(list.remove(0)).thenReturn("only-one");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            List<String> batch = source.readBatch(1);
            assertEquals(1, batch.size());
            assertEquals("only-one", batch.get(0));
        } finally {
            source.close();
        }
    }

    @Test
    void readAllReturnsEmptyListWhenEmpty() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(true);

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            assertTrue(source.readAll().isEmpty());
        } finally {
            source.close();
        }
    }

    @Test
    void deserializeValidJsonSuccessfully() {
        record Person(String name, int age) {}

        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(false, true);
        when(list.remove(0)).thenReturn("{\"name\":\"Alice\",\"age\":30}");

        RedisListSource<Person> source = new RedisListSource<>(redisson, "l", new ObjectMapper(), Person.class);
        try {
            List<Person> persons = source.readAll();
            assertEquals(1, persons.size());
            assertEquals("Alice", persons.get(0).name());
            assertEquals(30, persons.get(0).age());
        } finally {
            source.close();
        }
    }

    @Test
    void readMultipleElementsFromList() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(false, false, false, true);
        when(list.remove(0)).thenReturn("first", "second", "third");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            assertEquals(List.of("first", "second", "third"), source.readAll());
        } finally {
            source.close();
        }
    }

    @Test
    void readAllHandlesLargeList() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);

        // Simulate 10 elements (simplified for testing)
        when(list.isEmpty()).thenReturn(false, false, false, false, false,
                false, false, false, false, false, true);
        when(list.remove(0)).thenReturn("item-0", "item-1", "item-2", "item-3", "item-4",
                "item-5", "item-6", "item-7", "item-8", "item-9");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            List<String> items = source.readAll();
            assertEquals(10, items.size());
        } finally {
            source.close();
        }
    }

    @Test
    void pollBatchWithTimeoutWaitsForData() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(true, false);
        when(list.remove(0)).thenReturn("delayed-item");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            source.pollBatch(batch -> {
                // Consumer doesn't return a value
            }, 10, Duration.ofMillis(100));
        } finally {
            source.close();
        }
    }

    @Test
    void readBatchWithLargeBatchSize() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(false, true);
        when(list.remove(0)).thenReturn("single");

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            // Batch size larger than available items
            assertEquals(List.of("single"), source.readBatch(1000));
        } finally {
            source.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        source.close();
        source.close(); // Should not throw exception
    }

    @Test
    void deserializeWithCustomObjectMapper() {
        record Data(String value) {}

        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.isEmpty()).thenReturn(false, true);
        when(list.remove(0)).thenReturn("{\"value\":\"test\"}");

        ObjectMapper customMapper = new ObjectMapper();
        RedisListSource<Data> source = new RedisListSource<>(redisson, "l", customMapper, Data.class);
        try {
            List<Data> data = source.readAll();
            assertEquals(1, data.size());
            assertEquals("test", data.get(0).value());
        } finally {
            source.close();
        }
    }

    @Test
    void readOneWithNullElement() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(redisson.<String>getList("l")).thenReturn(list);
        when(list.remove(0)).thenReturn(null);

        RedisListSource<String> source = new RedisListSource<>(redisson, "l", String.class);
        try {
            assertNull(source.readOne());
        } finally {
            source.close();
        }
    }
}

