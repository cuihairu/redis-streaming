package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import io.github.cuihairu.redis.streaming.window.triggers.EventTimeTrigger;

import java.time.Duration;
import java.util.Collections;

/**
 * SessionWindow groups elements into sessions based on a gap of inactivity.
 *
 * @param <T> The type of elements
 */
public class SessionWindow<T> implements WindowAssigner<T> {

    private final long sessionGap;

    private SessionWindow(long sessionGap) {
        this.sessionGap = sessionGap;
    }

    public static <T> SessionWindow<T> withGap(Duration gap) {
        return new SessionWindow<>(gap.toMillis());
    }

    public static <T> SessionWindow<T> withGapMillis(long gapMillis) {
        return new SessionWindow<>(gapMillis);
    }

    @Override
    public Iterable<Window> assignWindows(T element, long timestamp) {
        // Each element initially creates its own session window
        // Windows will be merged if they overlap
        return Collections.singletonList(new TimeWindow(timestamp, timestamp + sessionGap));
    }

    @Override
    public Trigger<T> getDefaultTrigger() {
        return new EventTimeTrigger<>();
    }

    public long getSessionGap() {
        return sessionGap;
    }

    @Override
    public String toString() {
        return "SessionWindow{gap=" + sessionGap + "ms}";
    }

    /**
     * Check if two session windows should be merged
     */
    public static boolean shouldMerge(TimeWindow w1, TimeWindow w2) {
        return TimeWindow.intersects(w1, w2);
    }
}
