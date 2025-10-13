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
     * Consume an element from the stream
     *
     * @param value The element to consume
     * @throws Exception if the consumption fails
     */
    void invoke(T value) throws Exception;
}
