package io.github.cuihairu.redis.streaming.api.stream;

/**
 * The delivery guarantee a sink (or pipeline) provides for the elements it consumes.
 *
 * <p>Sinks declare their capability via {@link StreamSink#deliveryGuarantee()} so callers and
 * documentation can reason about end-to-end semantics instead of inferring them from
 * implementation details:</p>
 *
 * <ul>
 *   <li>{@link #AT_MOST_ONCE} — an element may be dropped under failure, but is never processed
 *       more than once.</li>
 *   <li>{@link #AT_LEAST_ONCE} — an element is never lost, but may be applied more than once
 *       after a crash/replay (duplicate application is possible).</li>
 *   <li>{@link #EFFECTIVELY_ONCE} — duplicate elements have no additional effect: either the
 *       write is deduplicated/atomic on the target (idempotent by record id), or the runtime
 *       coordinates the write with the checkpoint via two-phase commit. For sinks that reach an
 *       external system through an outbox, delivery out of Redis is at-least-once and the
 *       end-to-end guarantee holds only when the target deduplicates by the record id.</li>
 * </ul>
 */
public enum DeliveryGuarantee {
    AT_MOST_ONCE,
    AT_LEAST_ONCE,
    EFFECTIVELY_ONCE
}
