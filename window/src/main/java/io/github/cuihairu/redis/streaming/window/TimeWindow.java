package io.github.cuihairu.redis.streaming.window;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;

/**
 * TimeWindow represents a time-based window with a start and end timestamp.
 */
public class TimeWindow implements WindowAssigner.Window {

    private final long start;
    private final long end;

    public TimeWindow(long start, long end) {
        this.start = start;
        this.end = end;
    }

    @Override
    public long getStart() {
        return start;
    }

    @Override
    public long getEnd() {
        return end;
    }

    public long maxTimestamp() {
        return end - 1;
    }

    public boolean contains(long timestamp) {
        return timestamp >= start && timestamp < end;
    }

    public long getSize() {
        return end - start;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TimeWindow that = (TimeWindow) o;
        return start == that.start && end == that.end;
    }

    @Override
    public int hashCode() {
        return (int) (start ^ end);
    }

    @Override
    public String toString() {
        return "TimeWindow{" + start + " - " + end + '}';
    }

    /**
     * Merge two windows if they overlap or are adjacent
     */
    public static TimeWindow merge(TimeWindow w1, TimeWindow w2) {
        return new TimeWindow(Math.min(w1.start, w2.start), Math.max(w1.end, w2.end));
    }

    /**
     * Check if two windows intersect
     */
    public static boolean intersects(TimeWindow w1, TimeWindow w2) {
        return w1.start < w2.end && w2.start < w1.end;
    }
}
