package io.github.cuihairu.redis.streaming.api.watermark;

import java.io.Serializable;

/**
 * Extracts event-time timestamps from elements.
 *
 * <p>The {@code recordTimestamp} is the timestamp associated with the record in the runtime/source
 * (if any). Implementations can use it as a fallback when the event does not carry its own timestamp.</p>
 *
 * @param <T> element type
 */
@FunctionalInterface
public interface TimestampAssigner<T> extends Serializable {

    /**
     * Extract the event-time timestamp.
     *
     * @param element         the element
     * @param recordTimestamp the current record timestamp from the runtime/source
     * @return the extracted event-time timestamp (milliseconds)
     */
    long extractTimestamp(T element, long recordTimestamp);
}

