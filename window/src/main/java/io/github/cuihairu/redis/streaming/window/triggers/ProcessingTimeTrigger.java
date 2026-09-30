package io.github.cuihairu.redis.streaming.window.triggers;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;

/**
 * ProcessingTimeTrigger fires when the processing time passes the end of the window.
 *
 * <p>The trigger does not register timers itself: {@link #onElement} is a no-op, and the
 * owning operator must invoke {@link #onProcessingTime} as processing time advances past
 * {@code window.getEnd()} — that call is the only fire path.
 *
 * @param <T> The type of elements
 */
public class ProcessingTimeTrigger<T> implements WindowAssigner.Trigger<T> {

    @Override
    public WindowAssigner.TriggerResult onElement(T element, long timestamp, WindowAssigner.Window window) {
        // No timer registration here; the runtime must call onProcessingTime to fire
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
