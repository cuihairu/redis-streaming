package io.github.cuihairu.redis.streaming.sink.collection;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;

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

    @Test
    void testWithLinkedList() throws Exception {
        LinkedList<String> list = new LinkedList<>();
        CollectionSink<String> sink = new CollectionSink<>(list);

        sink.invoke("first");
        sink.invoke("second");

        assertEquals(2, sink.size());
        assertEquals("first", list.getFirst());
        assertEquals("second", list.getLast());
    }

    @Test
    void testWithVector() throws Exception {
        Vector<String> vector = new Vector<>();
        CollectionSink<String> sink = new CollectionSink<>(vector);

        sink.invoke("a");
        sink.invoke("b");

        assertEquals(2, sink.size());
        assertTrue(vector.contains("a"));
        assertTrue(vector.contains("b"));
    }

    @Test
    void testWithCopyOnWriteArrayList() throws Exception {
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>();
        CollectionSink<String> sink = new CollectionSink<>(list);

        sink.invoke("thread-safe-1");
        sink.invoke("thread-safe-2");

        assertEquals(2, sink.size());
        assertTrue(list.contains("thread-safe-1"));
    }

    @Test
    void testNullValues() throws Exception {
        CollectionSink<String> sink = new CollectionSink<>();

        sink.invoke("a");
        sink.invoke(null);
        sink.invoke("b");

        assertEquals(3, sink.size());
        assertEquals("a", sink.getList().get(0));
        assertNull(sink.getList().get(1));
        assertEquals("b", sink.getList().get(2));
    }

    @Test
    void testEmptyElements() throws Exception {
        CollectionSink<String> sink = new CollectionSink<>();

        sink.invoke("");
        sink.invoke("not-empty");
        sink.invoke("");

        assertEquals(3, sink.size());
        assertEquals("", sink.getList().get(0));
        assertEquals("not-empty", sink.getList().get(1));
        assertEquals("", sink.getList().get(2));
    }

    @Test
    void testDifferentTypes() throws Exception {
        CollectionSink<Object> sink = new CollectionSink<>();

        sink.invoke("string");
        sink.invoke(42);
        sink.invoke(3.14);
        sink.invoke(true);
        sink.invoke('c');

        assertEquals(5, sink.size());
        assertEquals("string", sink.getList().get(0));
        assertEquals(42, sink.getList().get(1));
        assertEquals(3.14, sink.getList().get(2));
        assertEquals(true, sink.getList().get(3));
        assertEquals('c', sink.getList().get(4));
    }

    @Test
    void testLargeCollection() throws Exception {
        CollectionSink<Integer> sink = new CollectionSink<>();

        for (int i = 0; i < 1000; i++) {
            sink.invoke(i);
        }

        assertEquals(1000, sink.size());
        assertEquals(0, sink.getList().get(0));
        assertEquals(999, sink.getList().get(999));
    }

    @Test
    void testToString() throws Exception {
        CollectionSink<String> sink = new CollectionSink<>();

        sink.invoke("a");
        sink.invoke("b");

        String str = sink.toString();
        assertTrue(str.contains("CollectionSink"));
    }

    @Test
    void testGetCollectionReturnsSameInstance() throws Exception {
        List<String> originalList = new ArrayList<>();
        CollectionSink<String> sink = new CollectionSink<>(originalList);

        assertSame(originalList, sink.getCollection());

        Collection<String> retrievedCollection = sink.getCollection();
        assertSame(originalList, retrievedCollection);
    }

    @Test
    void testMultipleClears() throws Exception {
        CollectionSink<String> sink = new CollectionSink<>();

        sink.invoke("a");
        sink.clear();
        sink.invoke("b");
        sink.clear();
        sink.invoke("c");

        assertEquals(1, sink.size());
        assertEquals("c", sink.getList().get(0));
    }

    @Test
    void testClearEmptyCollection() throws Exception {
        CollectionSink<String> sink = new CollectionSink<>();

        assertEquals(0, sink.size());
        sink.clear(); // Should not throw
        assertEquals(0, sink.size());
    }

    @Test
    void testSizeAfterClear() throws Exception {
        CollectionSink<String> sink = new CollectionSink<>();

        sink.invoke("a");
        sink.invoke("b");
        sink.invoke("c");
        assertEquals(3, sink.size());

        sink.clear();
        assertEquals(0, sink.size());

        sink.invoke("d");
        assertEquals(1, sink.size());
    }
}
