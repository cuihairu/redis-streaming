package io.github.cuihairu.redis.streaming.window.triggers;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;

/**
 * CountTrigger fires when the window contains a specified number of elements.
 *
 * @param <T> The type of elements
 */
public class CountTrigger<T> implements WindowAssigner.Trigger<T> {

    private final long maxCount;
    private long currentCount = 0;

    public CountTrigger(long maxCount) {
        this.maxCount = maxCount;
    }

    public static <T> CountTrigger<T> of(long count) {
        return new CountTrigger<>(count);
    }

    @Override
    public WindowAssigner.TriggerResult onElement(T element, long timestamp, WindowAssigner.Window window) {
        currentCount++;
        if (currentCount >= maxCount) {
            currentCount = 0;
            return WindowAssigner.TriggerResult.FIRE;
        }
        return WindowAssigner.TriggerResult.CONTINUE;
    }

    @Override
    public WindowAssigner.TriggerResult onProcessingTime(long time, WindowAssigner.Window window) {
        return WindowAssigner.TriggerResult.CONTINUE;
    }

    @Override
    public WindowAssigner.TriggerResult onEventTime(long time, WindowAssigner.Window window) {
        return WindowAssigner.TriggerResult.CONTINUE;
    }

    @Override
    public String toString() {
        return "CountTrigger{maxCount=" + maxCount + '}';
    }
}
