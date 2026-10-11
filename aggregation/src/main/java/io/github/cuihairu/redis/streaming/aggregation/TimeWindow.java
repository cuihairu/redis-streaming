package io.github.cuihairu.redis.streaming.aggregation;

import java.time.Duration;
import java.time.Instant;

/**
 * Time window specification for aggregation operations
 *
 * @deprecated same name, different type than the pipeline window
 *     {@link io.github.cuihairu.redis.streaming.window.TimeWindow} (millisecond interval,
 *     produced by the window module's assigners). Pipeline code should use the window
 *     module (assigners/triggers) so windows feed into {@code WindowedStream}; the
 *     Duration-based specs here are schedule descriptions only. Slated for removal after
 *     the deprecation period.
 */
@Deprecated
public interface TimeWindow {

    /**
     * Get the window size/duration
     *
     * @return window duration
     */
    Duration getSize();

    /**
     * Get the window slide interval (for sliding windows)
     *
     * @return slide duration, null for tumbling windows
     */
    Duration getSlide();

    /**
     * Check if this is a sliding window
     *
     * @return true if sliding, false if tumbling
     */
    default boolean isSliding() {
        return getSlide() != null && !getSlide().equals(getSize());
    }

    /**
     * Get the window start time for a given timestamp
     *
     * @param timestamp the timestamp to find window for
     * @return window start time
     */
    Instant getWindowStart(Instant timestamp);

    /**
     * Get the window end time for a given timestamp
     *
     * @param timestamp the timestamp to find window for
     * @return window end time
     */
    Instant getWindowEnd(Instant timestamp);

    /**
     * Check if a timestamp falls within this window
     *
     * @param timestamp the timestamp to check
     * @param windowStart the window start time
     * @return true if timestamp is within window
     */
    default boolean contains(Instant timestamp, Instant windowStart) {
        Instant windowEnd = windowStart.plus(getSize());
        return !timestamp.isBefore(windowStart) && timestamp.isBefore(windowEnd);
    }
}