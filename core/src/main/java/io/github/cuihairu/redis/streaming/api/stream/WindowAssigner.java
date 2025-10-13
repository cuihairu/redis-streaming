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
     */
    Trigger<T> getDefaultTrigger();

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
