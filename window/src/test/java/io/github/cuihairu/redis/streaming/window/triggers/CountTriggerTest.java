package io.github.cuihairu.redis.streaming.window.triggers;

import io.github.cuihairu.redis.streaming.api.stream.WindowAssigner;
import io.github.cuihairu.redis.streaming.window.TimeWindow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CountTrigger
 */
class CountTriggerTest {

    @Test
    void testOfFactoryMethod() {
        // Given & When
        CountTrigger<String> trigger = CountTrigger.of(5);

        // Then
        assertNotNull(trigger);
        // Verify through behavior - should fire after 5 elements
        TimeWindow window = new TimeWindow(0, 5000);
        for (int i = 0; i < 4; i++) {
            assertEquals(WindowAssigner.TriggerResult.CONTINUE,
                trigger.onElement("test", i, window));
        }
        assertEquals(WindowAssigner.TriggerResult.FIRE,
            trigger.onElement("test", 4, window));
    }

    @Test
    void testConstructor() {
        // Given & When
        CountTrigger<String> trigger = new CountTrigger<>(10);

        // Then
        // Verify through behavior
        TimeWindow window = new TimeWindow(0, 5000);
        for (int i = 0; i < 9; i++) {
            assertEquals(WindowAssigner.TriggerResult.CONTINUE,
                trigger.onElement("test", i, window));
        }
        assertEquals(WindowAssigner.TriggerResult.FIRE,
            trigger.onElement("test", 9, window));
    }

    @Test
    void testOnElementCountNotReached() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(5);
        String element = "test";
        long timestamp = 1000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result1 = trigger.onElement(element, timestamp, window);
        WindowAssigner.TriggerResult result2 = trigger.onElement(element, timestamp, window);
        WindowAssigner.TriggerResult result3 = trigger.onElement(element, timestamp, window);

        // Then - count not reached yet
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result1);
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result2);
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result3);
    }

    @Test
    void testOnElementCountReached() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(3);
        String element = "test";
        long timestamp = 1000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result1 = trigger.onElement(element, timestamp, window);
        WindowAssigner.TriggerResult result2 = trigger.onElement(element, timestamp, window);
        WindowAssigner.TriggerResult result3 = trigger.onElement(element, timestamp, window);

        // Then - third element should trigger fire
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result1);
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result2);
        assertEquals(WindowAssigner.TriggerResult.FIRE, result3);
    }

    @Test
    void testOnElementCountResetsAfterFire() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(2);
        TimeWindow window = new TimeWindow(0, 5000);

        // When - reach count, fire, then add more elements
        trigger.onElement("test1", 1000, window);
        trigger.onElement("test2", 2000, window); // fires here, count resets to 0
        WindowAssigner.TriggerResult result3 = trigger.onElement("test3", 3000, window); // count=1
        WindowAssigner.TriggerResult result4 = trigger.onElement("test4", 4000, window); // count=2, fires again

        // Then - count should reset after fire
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result3);
        assertEquals(WindowAssigner.TriggerResult.FIRE, result4);
    }

    @Test
    void testOnElementWithCountOfOne() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(1);
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onElement("test", 1000, window);

        // Then - first element should fire immediately
        assertEquals(WindowAssigner.TriggerResult.FIRE, result);
    }

    @Test
    void testOnProcessingTime() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(5);
        long time = 2000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onProcessingTime(time, window);

        // Then - count trigger doesn't react to processing time
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testOnEventTime() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(5);
        long time = 6000;
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onEventTime(time, window);

        // Then - count trigger doesn't react to event time
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, result);
    }

    @Test
    void testToString() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(10);

        // When
        String str = trigger.toString();

        // Then
        assertEquals("CountTrigger{maxCount=10}", str);
    }

    @Test
    void testWithLargeCount() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(1000);
        TimeWindow window = new TimeWindow(0, 5000);

        // When - add 999 elements
        for (int i = 0; i < 999; i++) {
            assertEquals(WindowAssigner.TriggerResult.CONTINUE,
                trigger.onElement("test" + i, i, window));
        }

        // Then - 1000th element should fire
        assertEquals(WindowAssigner.TriggerResult.FIRE,
            trigger.onElement("test999", 999, window));
    }

    @Test
    void testMultipleFireCycles() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(2);
        TimeWindow window = new TimeWindow(0, 5000);

        // When - first cycle
        trigger.onElement("test1", 1000, window);
        WindowAssigner.TriggerResult fire1 = trigger.onElement("test2", 2000, window);
        // second cycle - after fire, count resets to 0
        trigger.onElement("test3", 3000, window); // count=1
        trigger.onElement("test4", 4000, window); // count=2, fires
        // Wait, we need to check fire2 is the result of test4
        // third cycle
        trigger.onElement("test5", 5000, window); // count=1
        trigger.onElement("test6", 6000, window); // count=2, fires

        // Then - need to track properly
        assertEquals(WindowAssigner.TriggerResult.FIRE, fire1);
        // After fire1, count=0
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onElement("test3", 3000, window));
        assertEquals(WindowAssigner.TriggerResult.FIRE, trigger.onElement("test4", 4000, window));
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, trigger.onElement("test5", 5000, window));
        assertEquals(WindowAssigner.TriggerResult.FIRE, trigger.onElement("test6", 6000, window));
    }

    @Test
    void testWithDifferentElementTypes() {
        // Given
        CountTrigger<Integer> intTrigger = CountTrigger.of(2);
        CountTrigger<String> stringTrigger = CountTrigger.of(2);
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        intTrigger.onElement(1, 1000, window);
        WindowAssigner.TriggerResult intResult = intTrigger.onElement(2, 2000, window);

        stringTrigger.onElement("a", 1000, window);
        WindowAssigner.TriggerResult stringResult = stringTrigger.onElement("b", 2000, window);

        // Then - both should fire
        assertEquals(WindowAssigner.TriggerResult.FIRE, intResult);
        assertEquals(WindowAssigner.TriggerResult.FIRE, stringResult);
    }

    @Test
    void testTriggerIsStateful() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(3);
        TimeWindow window = new TimeWindow(0, 5000);

        // When - add elements one at a time
        // Note: can't access internal count directly, verify through behavior
        WindowAssigner.TriggerResult r1 = trigger.onElement("test1", 1000, window);
        WindowAssigner.TriggerResult r2 = trigger.onElement("test2", 2000, window);
        WindowAssigner.TriggerResult r3 = trigger.onElement("test3", 3000, window); // fires and resets
        WindowAssigner.TriggerResult r4 = trigger.onElement("test4", 4000, window); // first after reset

        // Then - verify behavior indicates count reset
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, r1);
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, r2);
        assertEquals(WindowAssigner.TriggerResult.FIRE, r3);
        assertEquals(WindowAssigner.TriggerResult.CONTINUE, r4); // count is 1 after reset
    }

    @Test
    void testWithZeroCount() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(0);
        TimeWindow window = new TimeWindow(0, 5000);

        // When
        WindowAssigner.TriggerResult result = trigger.onElement("test", 1000, window);

        // Then - with maxCount=0, first element (currentCount=1) >= 0, so it fires
        assertEquals(WindowAssigner.TriggerResult.FIRE, result);
    }

    @Test
    void testMultipleWindowsIndependent() {
        // Given
        CountTrigger<String> trigger = CountTrigger.of(2);
        TimeWindow window1 = new TimeWindow(0, 5000);
        TimeWindow window2 = new TimeWindow(5000, 10000);

        // When - add elements to window1
        trigger.onElement("test1", 1000, window1);
        trigger.onElement("test2", 2000, window1); // fires

        // Then - adding to window2 should still count
        // Note: CountTrigger implementation doesn't distinguish windows,
        // so the count is shared. This test documents current behavior.
        trigger.onElement("test3", 6000, window2);
        WindowAssigner.TriggerResult result = trigger.onElement("test4", 7000, window2);
        assertEquals(WindowAssigner.TriggerResult.FIRE, result);
    }
}
