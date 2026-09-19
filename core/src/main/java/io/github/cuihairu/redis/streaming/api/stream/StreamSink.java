package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;

/**
 * StreamSink is a sink that consumes elements from a stream.
 *
 * @param <T> The type of elements
 */
@FunctionalInterface
public interface StreamSink<T> extends Serializable {

    /**
     * Called once by the runtime before the first element is delivered to this sink.
     *
     * <p>Use this hook to acquire resources such as connections, files or clients.
     * The default implementation is a no-op, preserving compatibility with existing sinks.</p>
     *
     * @throws Exception if the sink cannot be initialized
     */
    default void open() throws Exception {
    }

    /**
     * Consume an element from the stream
     *
     * @param value The element to consume
     * @throws Exception if the consumption fails
     */
    void invoke(T value) throws Exception;

    /**
     * Called once by the runtime when the stream finishes or the job is cancelled.
     *
     * <p>Use this hook to release resources acquired in {@link #open()}. Implementations must be
     * idempotent; runtimes log but do not propagate exceptions thrown from this method.
     * The default implementation is a no-op.</p>
     *
     * @throws Exception if the resource release fails
     */
    default void close() throws Exception {
    }
}
