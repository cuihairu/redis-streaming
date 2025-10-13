package io.github.cuihairu.redis.streaming.window.triggers;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;

/**
 * EventTimeTrigger fires when the watermark passes the end of the window.
 *
 * @param <T> The type of elements
 */
public class EventTimeTrigger<T> implements WindowAssigner.Trigger<T> {

    @Override
    public WindowAssigner.TriggerResult onElement(T element, long timestamp, WindowAssigner.Window window) {
        // Continue accumulating elements
        return WindowAssigner.TriggerResult.CONTINUE;
    }

    @Override
    public WindowAssigner.TriggerResult onProcessingTime(long time, WindowAssigner.Window window) {
        // Event time trigger doesn't react to processing time
        return WindowAssigner.TriggerResult.CONTINUE;
    }

    @Override
    public WindowAssigner.TriggerResult onEventTime(long time, WindowAssigner.Window window) {
        // Fire when watermark passes the window end
        if (time >= window.getEnd()) {
            return WindowAssigner.TriggerResult.FIRE_AND_PURGE;
        }
        return WindowAssigner.TriggerResult.CONTINUE;
    }

    @Override
    public String toString() {
        return "EventTimeTrigger";
    }
}
