package io.github.cuihairu.redis.streaming.aggregation;

import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Sliding time window implementation
 */
@Data
public class SlidingWindow implements TimeWindow {

    private final Duration size;
    private final Duration slide;

    // Explicit constructor replacing @AllArgsConstructor so the B-11 guards cannot be
    // bypassed by constructing the class directly.
    public SlidingWindow(Duration size, Duration slide) {
        if (size == null || size.isZero() || size.isNegative()) {
            throw new IllegalArgumentException("SlidingWindow size must be a positive duration, got " + size);
        }
        if (slide == null || slide.isZero() || slide.isNegative()) {
            throw new IllegalArgumentException("SlidingWindow slide must be a positive duration, got " + slide);
        }
        this.size = size;
        this.slide = slide;
    }

    @Override
    public Instant getWindowStart(Instant timestamp) {
        long epochMilli = timestamp.toEpochMilli();
        long slideMs = slide.toMillis();
        // floorDiv (not /) keeps pre-epoch timestamps aligned into the window before them (B-21).
        long windowStart = Math.floorDiv(epochMilli, slideMs) * slideMs;
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
        long startWindow = Math.floorDiv(earliestStart, slideMs) * slideMs;

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
        // Construct first so null/zero/negative arguments fail the constructor guards with a
        // clear IAE instead of an NPE from the compareTo below.
        SlidingWindow window = new SlidingWindow(size, slide);
        if (slide.compareTo(size) > 0) {
            throw new IllegalArgumentException("Slide duration cannot be larger than window size");
        }
        return window;
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