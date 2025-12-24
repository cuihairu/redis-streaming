package io.github.cuihairu.redis.streaming.window.triggers;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CountTriggerTest {

    @Test
    void firesAndResetsAfterMaxCount() {
        CountTrigger<String> trigger = CountTrigger.of(3);
        TimeWindow window = new TimeWindow(0, 10);

        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onElement("a", 1, window));
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onElement("b", 2, window));
        assertEquals(WindowAssigner.TriggerResult.FIRE, trigger.onElement("c", 3, window));

        // After FIRE, counter resets.
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onElement("d", 4, window));
        assertEquals("CountTrigger{maxCount=3}", trigger.toString());
    }

    @Test
    void ignoresProcessingAndEventTime() {
        CountTrigger<String> trigger = CountTrigger.of(1);
        TimeWindow window = new TimeWindow(0, 10);

        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onProcessingTime(10, window));
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onEventTime(10, window));
    }
}

