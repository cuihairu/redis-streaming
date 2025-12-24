package io.github.cuihairu.redis.streaming.source.file;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsLinesFromFile() throws Exception {
        Path file = tempDir.resolve("in.txt");
        Files.writeString(file, "a\nb\nc\n");

        FileSource source = new FileSource(file);
        List<String> out = new ArrayList<>();
        source.run(new CollectingContext<>(out));

        assertEquals(List.of("a", "b", "c"), out);
    }

    @Test
    void cancelStopsEarly() throws Exception {
        Path file = tempDir.resolve("in.txt");
        Files.writeString(file, "a\nb\nc\n");

        FileSource source = new FileSource(file);
        List<String> out = new ArrayList<>();
        source.run(new StreamSource.SourceContext<>() {
            @Override
            public void collect(String element) {
                out.add(element);
                source.cancel();
            }

            @Override
            public void collectWithTimestamp(String element, long timestamp) {
                collect(element);
            }

            @Override
            public Object getCheckpointLock() {
                return this;
            }

            @Override
            public boolean isStopped() {
                return false;
            }
        });

        assertEquals(List.of("a"), out);
    }

    @Test
    void wrapsIoExceptionsWithFilePath() {
        FileSource source = new FileSource(tempDir.resolve("missing.txt"));

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                source.run(new CollectingContext<>(new ArrayList<>())));
        assertTrue(ex.getMessage().contains("Failed to read file"));
        assertNotNull(ex.getCause());
    }

    private static final class CollectingContext<T> implements StreamSource.SourceContext<T> {
        private final List<T> out;
        private final Object lock = new Object();

        private CollectingContext(List<T> out) {
            this.out = out;
        }

        @Override
        public void collect(T element) {
            out.add(element);
        }

        @Override
        public void collectWithTimestamp(T element, long timestamp) {
            out.add(element);
        }

        @Override
        public Object getCheckpointLock() {
            return lock;
        }

        @Override
        public boolean isStopped() {
            return false;
        }
    }
}

