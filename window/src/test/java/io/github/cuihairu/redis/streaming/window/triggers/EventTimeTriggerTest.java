package io.github.cuihairu.redis.streaming.window.triggers;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EventTimeTrigger
 */
class EventTimeTriggerTest {

    @Test
    void testOnElement() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        String element = "test";
        long timestamp = 1000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onElement(element, timestamp, window);

        // Then - event time trigger continues accumulating elements
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testOnProcessingTime() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        long time = 2000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(time, window);

        // Then - event time trigger doesn't react to processing time
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testOnEventTimeBeforeWindowEnd() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        long time = 3000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onEventTime(time, window);

        // Then - watermark hasn't passed window end
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testOnEventTimeAtWindowEnd() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        long time = 5000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onEventTime(time, window);

        // Then - watermark equals window end, should fire
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }

    @Test
    void testOnEventTimeAfterWindowEnd() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        long time = 6000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onEventTime(time, window);

        // Then - watermark has passed window end
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }

    @Test
    void testOnEventTimeExactlyAtBoundary() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        TimeWindow window = new TimeWindow(1000, 5000);
        long time = 5000;

        // When
        WindowAssigner.TriggerResult result = trigger.onEventTime(time, window);

        // Then
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }

    @Test
    void testOnEventTimeJustBeforeBoundary() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        TimeWindow window = new TimeWindow(1000, 5000);
        long time = 4999;

        // When
        WindowAssigner.TriggerResult result = trigger.onEventTime(time, window);

        // Then
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testToString() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();

        // When
        String str = trigger.toString();

        // Then
        assertEquals("EventTimeTrigger", str);
    }

    @Test
    void testMultipleWindows() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        TimeWindow window1 = new TimeWindow(0, 5000);
        TimeWindow window2 = new TimeWindow(5000, 10000);
        long watermark = 6000;

        // When
        WindowAssigner.TriggerResult result1 = trigger.onEventTime(watermark, window1);
        WindowAssigner.TriggerResult result2 = trigger.onEventTime(watermark, window2);

        // Then - first window should fire, second should continue
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result1);
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result2);
    }

    @Test
    void testOnElementWithDifferentTypes() {
        // Given
        EventTimeTrigger<Integer> intTrigger = new EventTimeTrigger<>();
        EventTimeTrigger<String> stringTrigger = new EventTimeTrigger<>();
        EventTimeTrigger<Object> objectTrigger = new EventTimeTrigger<>();

        // When
        WindowAssigner.TriggerResult intResult = intTrigger.onElement(123, 1000, new TimeWindow(0, 5000));
        WindowAssigner.TriggerResult stringResult = stringTrigger.onElement("test", 1000, new TimeWindow(0, 5000));
        WindowAssigner.TriggerResult objectResult = objectTrigger.onElement(new Object(), 1000, new TimeWindow(0, 5000));

        // Then - all should continue
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, intResult);
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, stringResult);
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, objectResult);
    }

    @Test
    void testWithZeroLengthWindow() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        TimeWindow window = new TimeWindow(1000, 1000);
        long watermark = 1000;

        // When
        WindowAssigner.TriggerResult result = trigger.onEventTime(watermark, window);

        // Then - zero-length window should fire immediately
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }

    @Test
    void testWithLargeTimestamp() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        TimeWindow window = new TimeWindow(1000000, 2000000);
        long watermark = 1500000;

        // When
        WindowAssigner.TriggerResult result = trigger.onEventTime(watermark, window);

        // Then
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testTriggerIsStateless() {
        // Given
        EventTimeTrigger<String> trigger = new EventTimeTrigger<>();
        TimeWindow window = new TimeWindow(0, 5000);

        // When - call onElement multiple times
        trigger.onElement("test1", 1000, window);
        trigger.onElement("test2", 2000, window);
        trigger.onElement("test3", 3000, window);
        WindowAssigner.TriggerResult result = trigger.onEventTime(5000, window);

        // Then - trigger should still fire regardless of element count
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }
}
