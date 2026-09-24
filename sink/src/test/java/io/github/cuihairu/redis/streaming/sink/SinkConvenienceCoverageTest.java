package io.github.cuihairu.redis.streaming.sink;

import io.github.cuihairu.redis.streaming.sink.collection.CollectionSink;
import io.github.cuihairu.redis.streaming.sink.print.PrintSink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers PrintSink prefix constructor and CollectionSink#getList branches. */
class SinkConvenienceCoverageTest {

    @Test
    void printSinkWithPrefixFormatsOutput() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintSink<String> sink = new PrintSink<>(new PrintStream(buffer), "tag", true);
        try {
            sink.invoke("hello");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        String out = buffer.toString();
        assertTrue(out.contains("tag: hello"), out);

        PrintSink<String> prefixOnly = new PrintSink<>("pre");
        try {
            prefixOnly.invoke("x");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void collectionSinkGetListReturnsListOrNull() {
        List<String> backing = new ArrayList<>();
        CollectionSink<String> withList = new CollectionSink<>(backing);
        withList.invoke("a");
        assertEquals(List.of("a"), withList.getList());
        assertEquals(1, withList.size());

        CollectionSink<String> withSet = new CollectionSink<>(new java.util.LinkedHashSet<>());
        withSet.invoke("b");
        assertNull(withSet.getList(), "non-list collections report null");
    }
}
