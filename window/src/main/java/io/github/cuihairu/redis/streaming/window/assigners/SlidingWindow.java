package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import io.github.cuihairu.redis.streaming.window.triggers.EventTimeTrigger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SlidingWindow assigns elements to fixed-size, overlapping windows.
 *
 * @param <T> The type of elements
 */
public class SlidingWindow<T> implements WindowAssigner<T> {

    private final long size;
    private final long slide;

    private SlidingWindow(long size, long slide) {
        this.size = size;
        this.slide = slide;
    }

    public static <T> SlidingWindow<T> of(Duration size, Duration slide) {
        return new SlidingWindow<>(size.toMillis(), slide.toMillis());
    }

    public static <T> SlidingWindow<T> ofMillis(long sizeMillis, long slideMillis) {
        return new SlidingWindow<>(sizeMillis, slideMillis);
    }

    @Override
    public Iterable<Window> assignWindows(T element, long timestamp) {
        List<Window> windows = new ArrayList<>();

        long lastStart = timestamp - (timestamp % slide);

        for (long start = lastStart; start > timestamp - size; start -= slide) {
            if (start + size > timestamp) {
                windows.add(new TimeWindow(start, start + size));
            }
        }

        return windows;
    }

    @Override
    public Trigger<T> getDefaultTrigger() {
        return new EventTimeTrigger<>();
    }

    public long getSize() {
        return size;
    }

    public long getSlide() {
        return slide;
    }

    @Override
    public String toString() {
        return "SlidingWindow{size=" + size + "ms, slide=" + slide + "ms}";
    }
}
