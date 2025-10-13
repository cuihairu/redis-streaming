package io.github.cuihairu.redis.streaming.sink.print;

import io.github.cuihairu.redis.streaming.api.stream.StreamSink;

import java.io.PrintStream;

/**
 * A sink that prints elements to a PrintStream (default: System.out).
 *
 * This is useful for debugging and testing.
 *
 * @param <T> The type of elements
 */
public class PrintSink<T> implements StreamSink<T> {

    private static final long serialVersionUID = 1L;

    private final PrintStream output;
    private final String prefix;
    private final boolean withTimestamp;

    /**
     * Create a print sink that prints to System.out
     */
    public PrintSink() {
        this(System.out, "", false);
    }

    /**
     * Create a print sink with a prefix
     *
     * @param prefix Prefix to add before each element
     */
    public PrintSink(String prefix) {
        this(System.out, prefix, false);
    }

    /**
     * Create a print sink with custom output stream
     *
     * @param output The PrintStream to write to
     */
    public PrintSink(PrintStream output) {
        this(output, "", false);
    }

    /**
     * Create a print sink with all options
     *
     * @param output The PrintStream to write to
     * @param prefix Prefix to add before each element
     * @param withTimestamp Whether to include timestamp
     */
    public PrintSink(PrintStream output, String prefix, boolean withTimestamp) {
        this.output = output;
        this.prefix = prefix;
        this.withTimestamp = withTimestamp;
    }

    @Override
    public void invoke(T value) {
        StringBuilder sb = new StringBuilder();

        if (withTimestamp) {
            sb.append("[").append(System.currentTimeMillis()).append("] ");
        }

        if (prefix != null && !prefix.isEmpty()) {
            sb.append(prefix).append(": ");
        }

        sb.append(value);

        output.println(sb.toString());
    }

    /**
     * Create a print sink with timestamp
     */
    public static <T> PrintSink<T> withTimestamp() {
        return new PrintSink<>(System.out, "", true);
    }

    /**
     * Create a print sink with prefix and timestamp
     */
    public static <T> PrintSink<T> withPrefixAndTimestamp(String prefix) {
        return new PrintSink<>(System.out, prefix, true);
    }
}
