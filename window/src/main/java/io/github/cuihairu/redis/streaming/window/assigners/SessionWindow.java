package io.github.cuihairu.redis.streaming.window.assigners;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import io.github.cuihairu.redis.streaming.window.triggers.EventTimeTrigger;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;

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
        Objects.requireNonNull(gap, "gap");
        return withGapMillis(gap.toMillis());
    }

    public static <T> SessionWindow<T> withGapMillis(long gapMillis) {
        // A zero/negative gap yields empty [ts, ts) windows that fire on arrival and can
        // never merge with anything — silently turning every element into its own
        // fire-immediately session. Reject the configuration instead.
        if (gapMillis <= 0) {
            throw new IllegalArgumentException("session gap must be positive, got " + gapMillis + "ms");
        }
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

    @Override
    public boolean supportsWindowMerging() {
        // Every element seeds its own [ts, ts+gap) window; overlapping windows of the same key
        // must be coalesced into the union window (session semantics).
        return true;
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
