package io.github.cuihairu.redis.streaming.source.file;

import io.github.cuihairu.redis.streaming.api.stream.StreamSource;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A source that reads lines from a text file.
 *
 * Each line in the file is emitted as a separate element of type {@code String}.
 */
public class FileSource implements StreamSource<String> {

    private static final long serialVersionUID = 1L;

    private final String filePath;
    private volatile boolean running = true;

    /**
     * Create a file source
     *
     * @param filePath Path to the file to read
     */
    public FileSource(String filePath) {
        this.filePath = filePath;
    }

    /**
     * Create a file source from a Path
     *
     * @param path Path to the file to read
     */
    public FileSource(Path path) {
        this(path.toString());
    }

    @Override
    public void run(SourceContext<String> ctx) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                ctx.collect(line);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + filePath, e);
        }
    }

    @Override
    public void cancel() {
        running = false;
    }
}
