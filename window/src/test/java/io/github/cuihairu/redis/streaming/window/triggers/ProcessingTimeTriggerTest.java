package io.github.cuihairu.redis.streaming.window.triggers;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProcessingTimeTrigger
 */
class ProcessingTimeTriggerTest {

    @Test
    void testOnElement() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        String element = "test";
        long timestamp = 1000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onElement(element, timestamp, window);

        // Then - processing time trigger continues to register timer
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testOnProcessingTimeBeforeWindowEnd() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        long time = 3000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(time, window);

        // Then - processing time hasn't passed window end
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testOnProcessingTimeAtWindowEnd() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        long time = 5000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(time, window);

        // Then - processing time equals window end, should fire
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }

    @Test
    void testOnProcessingTimeAfterWindowEnd() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        long time = 6000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(time, window);

        // Then - processing time has passed window end
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }

    @Test
    void testOnEventTime() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        long time = 6000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onEventTime(time, window);

        // Then - processing time trigger doesn't react to event time
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testToString() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();

        // When
        String str = trigger.toString();

        // Then
        assertEquals("ProcessingTimeTrigger", str);
    }

    @Test
    void testMultipleWindows() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        TimeWindow window1 = new TimeWindow(0, 5000);
        TimeWindow window2 = new TimeWindow(5000, 10000);
        long processingTime = 6000;

        // When
        WindowAssigner.TriggerResult result1 = trigger.onProcessingTime(processingTime, window1);
        WindowAssigner.TriggerResult result2 = trigger.onProcessingTime(processingTime, window2);

        // Then - first window should fire, second should continue
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result1);
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result2);
    }

    @Test
    void testWithZeroLengthWindow() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        TimeWindow window = new TimeWindow(1000, 1000);
        long time = 1000;

        // When
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(time, window);

        // Then
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }

    @Test
    void testTriggerIsStateless() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        TimeWindow window = new TimeWindow(0, 5000);

        // When - call onElement multiple times
        trigger.onElement("test1", 1000, window);
        trigger.onElement("test2", 2000, window);
        trigger.onElement("test3", 3000, window);
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(5000, window);

        // Then - trigger should fire regardless of element count
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }

    @Test
    void testOnProcessingTimeJustBeforeBoundary() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        TimeWindow window = new TimeWindow(1000, 5000);
        long time = 4999;

        // When
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(time, window);

        // Then
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testOnProcessingTimeJustAfterBoundary() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        TimeWindow window = new TimeWindow(1000, 5000);
        long time = 5001;

        // When
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(time, window);

        // Then
        assertEquals(WindowAssigner.TriggerResult.FIRE_AND_PURGE, result);
    }

    @Test
    void testWithLargeTimestamp() {
        // Given
        ProcessingTimeTrigger<String> trigger = new ProcessingTimeTrigger<>();
        TimeWindow window = new TimeWindow(1000000, 2000000);
        long time = 1500000;

        // When
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(time, window);

        // Then
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }
}
