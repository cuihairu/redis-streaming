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

    /**
     * The delivery guarantee this sink provides for the elements it consumes.
     *
     * <p>The default is {@link DeliveryGuarantee#AT_LEAST_ONCE}: a plain sink applies every
     * element it is given exactly once per invocation, but the at-least-once replay semantics
     * of the consuming pipeline (process-then-ack with retries) mean an element can be applied
     * more than once across a crash/replay. Sinks that deduplicate by record id or coordinate
     * their writes with checkpoints override this to declare the stronger guarantee — see
     * {@link DeliveryGuarantee}.</p>
     */
    default DeliveryGuarantee deliveryGuarantee() {
        return DeliveryGuarantee.AT_LEAST_ONCE;
    }
}
