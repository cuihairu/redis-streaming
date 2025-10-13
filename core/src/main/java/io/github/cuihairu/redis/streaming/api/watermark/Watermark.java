package io.github.cuihairu.redis.streaming.api.watermark;

import java.io.Serializable;

/**
 * Watermark represents a timestamp indicating that no events with timestamp less than
 * the watermark will arrive.
 */
public class Watermark implements Serializable, Comparable<Watermark> {

    private static final long serialVersionUID = 1L;

    private final long timestamp;

    public Watermark(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public int compareTo(Watermark other) {
        return Long.compare(this.timestamp, other.timestamp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Watermark watermark = (Watermark) o;
        return timestamp == watermark.timestamp;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(timestamp);
    }

    @Override
    public String toString() {
        return "Watermark{" + timestamp + '}';
    }

    /**
     * Create a watermark indicating the maximum possible timestamp
     */
    public static Watermark maxWatermark() {
        return new Watermark(Long.MAX_VALUE);
    }
}
