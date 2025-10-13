package io.github.cuihairu.redis.streaming.sink.collection;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectionSinkTest {

    @Test
    void testCollectionSink() throws Exception {
        CollectionSink<String> sink = new CollectionSink<>();

        sink.invoke("a");
        sink.invoke("b");
        sink.invoke("c");

        assertEquals(3, sink.size());
        assertEquals(Arrays.asList("a", "b", "c"), sink.getList());
    }

    @Test
    void testWithCustomCollection() throws Exception {
        List<Integer> list = new ArrayList<>();
        CollectionSink<Integer> sink = new CollectionSink<>(list);

        sink.invoke(1);
        sink.invoke(2);
        sink.invoke(3);

        assertEquals(3, list.size());
        assertSame(list, sink.getCollection());
        assertEquals(Arrays.asList(1, 2, 3), list);
    }

    @Test
    void testClear() throws Exception {
        CollectionSink<String> sink = new CollectionSink<>();

        sink.invoke("a");
        sink.invoke("b");
        assertEquals(2, sink.size());

        sink.clear();
        assertEquals(0, sink.size());
        assertTrue(sink.getCollection().isEmpty());
    }

    @Test
    void testGetList() throws Exception {
        CollectionSink<String> sink = new CollectionSink<>();
        assertNotNull(sink.getList());

        sink.invoke("test");
        assertEquals(1, sink.getList().size());
    }
}
