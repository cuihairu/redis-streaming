package io.github.cuihairu.redis.streaming.api.stream;

import java.io.Serializable;

/**
 * WindowAssigner assigns elements to windows.
 *
 * @param <T> The type of elements
 */
public interface WindowAssigner<T> extends Serializable {

    /**
     * Assign an element to one or more windows
     *
     * @param element The element to assign
     * @param timestamp The timestamp of the element
     * @return The windows to which the element belongs
     */
    Iterable<Window> assignWindows(T element, long timestamp);

    /**
     * Get the default trigger for this window assigner
     *
     * <p><strong>Contract:</strong> windowing engines invoke this method once per (key, window)
     * bucket, so implementations must return a fresh trigger instance on every call. Stateful
     * triggers (for example count-based triggers keeping a per-window counter) must not share or
     * cache the returned instance across calls.
     */
    Trigger<T> getDefaultTrigger();

    /**
     * Whether this assigner produces <em>merging</em> windows: windows assigned to later elements
     * may overlap already-assigned windows of the same key and must then be coalesced into the
     * union window (session semantics).
     *
     * <p><strong>Contract:</strong> when this returns {@code true}, windowing engines merge every
     * bucket of a key whose {@code [start, end)} range intersects the newly assigned window into a
     * single bucket covering the union range, accumulating the elements. Trigger instances of the
     * merged-away buckets are dropped (this API has no trigger-merge callback), so merging
     * assigners should rely on stateless or timestamp-based triggers. When this returns
     * {@code false} (the default), overlapping windows stay separate buckets — required for
     * sliding windows, where shared elements are intentional.
     */
    default boolean supportsWindowMerging() {
        return false;
    }

    /**
     * Window represents a time window
     */
    interface Window {
        long getStart();
        long getEnd();
    }

    /**
     * Trigger determines when a window should be evaluated
     */
    interface Trigger<T> {
        TriggerResult onElement(T element, long timestamp, Window window);
        TriggerResult onProcessingTime(long time, Window window);
        TriggerResult onEventTime(long time, Window window);
    }

    enum TriggerResult {
        CONTINUE,
        FIRE,
        FIRE_AND_PURGE,
        PURGE
    }
}
