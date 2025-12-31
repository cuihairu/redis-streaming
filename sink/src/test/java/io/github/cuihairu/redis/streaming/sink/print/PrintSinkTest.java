package io.github.cuihairu.redis.streaming.sink.print;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

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

    @Test
    void testWithNullPrefix() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps, null, false);
        sink.invoke("test");

        String output = baos.toString();
        assertTrue(output.contains("test"));
    }

    @Test
    void testWithEmptyPrefix() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps, "", false);
        sink.invoke("test");

        String output = baos.toString();
        assertTrue(output.contains("test"));
        assertFalse(output.contains(":"));
    }

    @Test
    void testMultipleInvocations() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<Integer> sink = new PrintSink<>(ps, "NUM", false);
        for (int i = 0; i < 10; i++) {
            sink.invoke(i);
        }

        String output = baos.toString();
        assertEquals(10, output.lines().count());
    }

    @Test
    void testWithPrefixAndTimestamp() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps, "LOG", true);
        sink.invoke("message");

        String output = baos.toString();
        assertTrue(output.contains("LOG:"));
        assertTrue(output.contains("["));
        assertTrue(output.contains("message"));
    }

    @Test
    void testComplexObject() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<List<String>> sink = new PrintSink<>(ps);
        sink.invoke(List.of("a", "b", "c"));

        String output = baos.toString();
        assertTrue(output.contains("[") || output.contains("a"));
    }

    @Test
    void testNullInput() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps, "NULL", false);
        sink.invoke(null);

        String output = baos.toString();
        assertTrue(output.contains("NULL"));
    }

    @Test
    void testEmptyString() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps);
        sink.invoke("");

        String output = baos.toString();
        assertNotNull(output);
    }

    @Test
    void testSpecialCharacters() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps);
        sink.invoke("特殊字符\n换行符\t制表符");

        String output = baos.toString();
        assertTrue(output.contains("特殊字符"));
    }

    @Test
    void testLongMessage() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longMessage.append("x");
        }

        PrintSink<String> sink = new PrintSink<>(ps);
        sink.invoke(longMessage.toString());

        String output = baos.toString();
        assertEquals(1000, output.trim().length());
    }

    @Test
    void testSystemOutSink() {
        PrintSink<String> sink = new PrintSink<>(System.out);
        sink.invoke("test to stdout");
        // Should not throw exception
    }

    @Test
    void testSystemErrSink() {
        PrintSink<String> sink = new PrintSink<>(System.err);
        sink.invoke("test to stderr");
        // Should not throw exception
    }

    @Test
    void testWithTimestampFormat() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps, "TIME", true);
        sink.invoke("event");

        String output = baos.toString();
        // Timestamp should be in brackets
        assertTrue(output.contains("[") && output.contains("]"));
    }

    @Test
    void testNumericTypes() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<Object> sink = new PrintSink<>(ps);

        sink.invoke(42);
        sink.invoke(3.14);
        sink.invoke(100L);

        String output = baos.toString();
        assertTrue(output.contains("42"));
        assertTrue(output.contains("3.14"));
        assertTrue(output.contains("100"));
    }

    @Test
    void testBooleanType() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<Boolean> sink = new PrintSink<>(ps);
        sink.invoke(true);
        sink.invoke(false);

        String output = baos.toString();
        assertTrue(output.contains("true"));
        assertTrue(output.contains("false"));
    }

    @Test
    void testPrefixFormatting() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<String> sink = new PrintSink<>(ps, "INFO", false);
        sink.invoke("test");

        String output = baos.toString();
        assertTrue(output.contains("INFO: test"));
    }

    @Test
    void testDifferentObjectTypes() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);

        PrintSink<Object> sink = new PrintSink<>(ps);

        sink.invoke("string");
        sink.invoke(123);
        sink.invoke(45.67);
        sink.invoke(true);
        sink.invoke('c');

        String output = baos.toString();
        assertTrue(output.contains("string"));
        assertTrue(output.contains("123"));
        assertTrue(output.contains("45.67"));
        assertTrue(output.contains("true"));
    }
}
