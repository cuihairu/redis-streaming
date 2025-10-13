package io.github.cuihairu.redis.streaming.aggregation;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Sliding time window implementation
 */
@Data
@AllArgsConstructor
public class SlidingWindow implements TimeWindow {

    private final Duration size;
    private final Duration slide;

    @Override
    public Instant getWindowStart(Instant timestamp) {
        long epochMilli = timestamp.toEpochMilli();
        long slideMs = slide.toMillis();
        long windowStart = (epochMilli / slideMs) * slideMs;
        return Instant.ofEpochMilli(windowStart);
    }

    @Override
    public Instant getWindowEnd(Instant timestamp) {
        return getWindowStart(timestamp).plus(size);
    }

    /**
     * Get all overlapping windows for a given timestamp
     *
     * @param timestamp the timestamp
     * @return list of window start times that contain this timestamp
     */
    public List<Instant> getOverlappingWindows(Instant timestamp) {
        List<Instant> windows = new ArrayList<>();
        long timestampMs = timestamp.toEpochMilli();
        long sizeMs = size.toMillis();
        long slideMs = slide.toMillis();

        // Find the earliest window that could contain this timestamp
        long earliestStart = timestampMs - sizeMs + 1;
        long startWindow = (earliestStart / slideMs) * slideMs;

        // Generate all windows that contain this timestamp
        for (long windowStart = startWindow; windowStart <= timestampMs; windowStart += slideMs) {
            Instant windowStartTime = Instant.ofEpochMilli(windowStart);
            if (contains(timestamp, windowStartTime)) {
                windows.add(windowStartTime);
            }
        }

        return windows;
    }

    /**
     * Create a sliding window with specified size and slide
     *
     * @param size window size
     * @param slide slide interval
     * @return sliding window instance
     */
    public static SlidingWindow of(Duration size, Duration slide) {
        if (slide.compareTo(size) > 0) {
            throw new IllegalArgumentException("Slide duration cannot be larger than window size");
        }
        return new SlidingWindow(size, slide);
    }

    /**
     * Create a sliding window with size and slide in seconds
     *
     * @param sizeSeconds window size in seconds
     * @param slideSeconds slide interval in seconds
     * @return sliding window instance
     */
    public static SlidingWindow ofSeconds(long sizeSeconds, long slideSeconds) {
        return of(Duration.ofSeconds(sizeSeconds), Duration.ofSeconds(slideSeconds));
    }

    /**
     * Create a sliding window with size and slide in minutes
     *
     * @param sizeMinutes window size in minutes
     * @param slideMinutes slide interval in minutes
     * @return sliding window instance
     */
    public static SlidingWindow ofMinutes(long sizeMinutes, long slideMinutes) {
        return of(Duration.ofMinutes(sizeMinutes), Duration.ofMinutes(slideMinutes));
    }
}