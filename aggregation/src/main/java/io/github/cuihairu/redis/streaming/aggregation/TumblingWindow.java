package io.github.cuihairu.redis.streaming.aggregation;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;

/**
 * Tumbling time window implementation
 */
@Data
@AllArgsConstructor
public class TumblingWindow implements TimeWindow {

    private final Duration size;

    @Override
    public Duration getSlide() {
        return size; // Tumbling windows slide by their size
    }

    @Override
    public Instant getWindowStart(Instant timestamp) {
        long epochMilli = timestamp.toEpochMilli();
        long windowSizeMs = size.toMillis();
        long windowStart = (epochMilli / windowSizeMs) * windowSizeMs;
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