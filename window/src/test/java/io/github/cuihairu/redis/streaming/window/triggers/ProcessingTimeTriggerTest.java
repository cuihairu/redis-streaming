package io.github.cuihairu.redis.streaming.window.triggers;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessingTimeTriggerTest {

    @Test
    void firesWhenProcessingTimeReachesWindowEnd() {
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        TimeWindow window = new TimeWindow(0, 10);

        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onProcessingTime(9, window));
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, trigger.onProcessingTime(10, window));
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, trigger.onProcessingTime(11, window));
        assertEquals("ProcessingTimeTrigger", trigger.toString());
    }

    @Test
    void ignoresElementsAndEventTime() {
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        TimeWindow window = new TimeWindow(0, 10);

        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onElement("a", 1, window));
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onEventTime(10, window));
    }
}

