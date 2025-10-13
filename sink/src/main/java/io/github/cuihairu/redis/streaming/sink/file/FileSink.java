package io.github.cuihairu.redis.streaming.sink.file;

import io.github.cuihairu.redis.streaming.api.stream.StreamSink;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * A sink that writes elements to a file.
 *
 * Each element is written as a separate line.
 *
 * @param <T> The type of elements
 */
public class FileSink<T> implements StreamSink<T> {

    private static final long serialVersionUID = 1L;

    private final String filePath;
    private final boolean append;
    private transient BufferedWriter writer;

    /**
     * Create a file sink (overwrites existing file)
     *
     * @param filePath Path to the output file
     */
    public FileSink(String filePath) {
        this(filePath, false);
    }

    /**
     * Create a file sink from a Path
     *
     * @param path Path to the output file
     */
    public FileSink(Path path) {
        this(path.toString(), false);
    }

    /**
     * Create a file sink with append mode
     *
     * @param filePath Path to the output file
     * @param append Whether to append to existing file
     */
    public FileSink(String filePath, boolean append) {
        this.filePath = filePath;
        this.append = append;
    }

    @Override
    public void invoke(T value) throws Exception {
        if (writer == null) {
            initWriter();
        }

        writer.write(value.toString());
        writer.newLine();
        writer.flush(); // Flush immediately for safety
    }

    private void initWriter() throws IOException {
        writer = new BufferedWriter(new FileWriter(filePath, append));
    }

    /**
     * Close the writer (should be called when done)
     */
    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }
}
