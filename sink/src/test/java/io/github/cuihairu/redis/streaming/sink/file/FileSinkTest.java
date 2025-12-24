package io.github.cuihairu.redis.streaming.sink.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileSinkTest {

    @TempDir
    Path tempDir;

    @Test
    void writesLinesToFile() throws Exception {
        Path file = tempDir.resolve("out.txt");
        FileSink<String> sink = new FileSink<>(file);
        try {
            sink.invoke("a");
            sink.invoke("b");
        } finally {
            sink.close();
        }

        assertEquals(List.of("a", "b"), Files.readAllLines(file));
    }

    @Test
    void appendModeKeepsExistingContent() throws Exception {
        Path file = tempDir.resolve("out.txt");

        FileSink<String> sink1 = new FileSink<>(file.toString(), false);
        try {
            sink1.invoke("a");
        } finally {
            sink1.close();
        }

        FileSink<String> sink2 = new FileSink<>(file.toString(), true);
        try {
            sink2.invoke("b");
        } finally {
            sink2.close();
        }

        assertEquals(List.of("a", "b"), Files.readAllLines(file));
    }
}

