package io.github.cuihairu.redis.streaming.sink.print;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class PrintSinkTest {

    @Test
    void testPrintSink() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps);
        sink.invoke("test1");
        sink.invoke("test2");

        String output = baos.toString();
        assertTrue(output.contains("test1"));
        assertTrue(output.contains("test2"));
    }

    @Test
    void testWithPrefix() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps, "PREFIX", false);
        sink.invoke("test");

        String output = baos.toString();
        assertTrue(output.contains("PREFIX: test"));
    }

    @Test
    void testWithTimestamp() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps, "", true);
        sink.invoke("test");

        String output = baos.toString();
        assertTrue(output.contains("["));
        assertTrue(output.contains("]"));
        assertTrue(output.contains("test"));
    }

    @Test
    void testStaticFactoryMethods() {
        PrintSink<String> sink1 = PrintSink.withTimestamp();
        assertNotNull(sink1);

        PrintSink<String> sink2 = PrintSink.withPrefixAndTimestamp("TEST");
        assertNotNull(sink2);
    }
}
