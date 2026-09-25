package io.github.cuihairu.redis.streaming.sink.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers the null-writer guard of {@link FileSink#close()}: closing an unused sink must be a
 * harmless no-op and must not touch the file system.
 */
class FileSinkCloseGuardCoverageTest {

    @Test
    void closeBeforeAnyWriteIsANoOp(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("out.txt");
        FileSink<String> sink = new FileSink<>(target.toString());

        assertDoesNotThrow(sink::close);
        assertDoesNotThrow(sink::close);
        assertFalse(Files.exists(target), "closing an unused sink must not create the file");
    }

    @Test
    void pathConstructorAlsoSafeToClose(@TempDir Path dir) throws Exception {
        FileSink<String> sink = new FileSink<>(dir.resolve("other.txt"));
        assertDoesNotThrow(sink::close);
    }
}
