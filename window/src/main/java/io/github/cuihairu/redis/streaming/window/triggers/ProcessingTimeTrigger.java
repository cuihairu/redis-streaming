package io.github.cuihairu.redis.streaming.window.triggers;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;

/**
 * ProcessingTimeTrigger fires when the processing time passes the end of the window.
 *
 * @param <T> The type of elements
 */
public class ProcessingTimeTrigger<T> implements WindowAssigner.Trigger<T> {

    @Override
    public WindowAssigner.TriggerResult onElement(T element, long timestamp, WindowAssigner.Window window) {
        // Register a timer for the window end
        return WindowAssigner.TriggerResult.CONTINUE;
    }

    @Override
    public WindowAssigner.TriggerResult onProcessingTime(long time, WindowAssigner.Window window) {
        // Fire when processing time passes the window end
        if (time >= window.getEnd()) {
            return WindowAssigner.TriggerResult.FIRE_AND_PURGE;
        }
        return WindowAssigner.TriggerResult.CONTINUE;
    }

    @Override
    public WindowAssigner.TriggerResult onEventTime(long time, WindowAssigner.Window window) {
        // Processing time trigger doesn't react to event time
        return WindowAssigner.TriggerResult.CONTINUE;
    }

    @Override
    public String toString() {
        return "ProcessingTimeTrigger";
    }
}
