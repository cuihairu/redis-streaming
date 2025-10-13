package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import io.github.cuihairu.redis.streaming.window.triggers.EventTimeTrigger;

import java.time.Duration;
import java.util.Collections;

/**
 * TumblingWindow assigns elements to fixed-size, non-overlapping windows.
 *
 * @param <T> The type of elements
 */
public class TumblingWindow<T> implements WindowAssigner<T> {

    private final long size;

    private TumblingWindow(long size) {
        this.size = size;
    }

    public static <T> TumblingWindow<T> of(Duration duration) {
        return new TumblingWindow<>(duration.toMillis());
    }

    public static <T> TumblingWindow<T> ofMillis(long millis) {
        return new TumblingWindow<>(millis);
    }

    @Override
    public Iterable<Window> assignWindows(T element, long timestamp) {
        long start = timestamp - (timestamp % size);
        return Collections.singletonList(new TimeWindow(start, start + size));
    }

    @Override
    public Trigger<T> getDefaultTrigger() {
        return new EventTimeTrigger<>();
    }

    public long getSize() {
        return size;
    }

    @Override
    public String toString() {
        return "TumblingWindow{size=" + size + "ms}";
    }
}
