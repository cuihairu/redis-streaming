package io.github.cuihairu.redis.streaming.aggregation;

import lombok.Data;

import java.time.Duration;
import java.time.Instant;

/**
 * Tumbling time window implementation
 */
@Data
public class TumblingWindow implements TimeWindow {

    private final Duration size;

    // Explicit constructor replacing @AllArgsConstructor so the B-11 size guard cannot be
    // bypassed by constructing the record-style class directly.
    public TumblingWindow(Duration size) {
        if (size == null || size.isZero() || size.isNegative()) {
            throw new IllegalArgumentException("TumblingWindow size must be a positive duration, got " + size);
        }
        this.size = size;
    }

    @Override
    public Duration getSlide() {
        return size; // Tumbling windows slide by their size
    }

    @Override
    public Instant getWindowStart(Instant timestamp) {
        long epochMilli = timestamp.toEpochMilli();
        long windowSizeMs = size.toMillis();
        // floorDiv (not /) keeps pre-epoch timestamps aligned into the window before them (B-21).
        long windowStart = Math.floorDiv(epochMilli, windowSizeMs) * windowSizeMs;
        return Instant.ofEpochMilli(windowStart);
    }

    @Override
    public Instant getWindowEnd(Instant timestamp) {
        return getWindowStart(timestamp).plus(size);
    }

    /**
     * Create a tumbling window with specified size
     *
     * @param size window size
     * @return tumbling window instance
     */
    public static TumblingWindow of(Duration size) {
        return new TumblingWindow(size);
    }

    /**
     * Create a tumbling window with size in seconds
     *
     * @param seconds window size in seconds
     * @return tumbling window instance
     */
    public static TumblingWindow ofSeconds(long seconds) {
        return new TumblingWindow(Duration.ofSeconds(seconds));
    }

    /**
     * Create a tumbling window with size in minutes
     *
     * @param minutes window size in minutes
     * @return tumbling window instance
     */
    public static TumblingWindow ofMinutes(long minutes) {
        return new TumblingWindow(Duration.ofMinutes(minutes));
    }

    /**
     * Create a tumbling window with size in hours
     *
     * @param hours window size in hours
     * @return tumbling window instance
     */
    public static TumblingWindow ofHours(long hours) {
        return new TumblingWindow(Duration.ofHours(hours));
    }
}