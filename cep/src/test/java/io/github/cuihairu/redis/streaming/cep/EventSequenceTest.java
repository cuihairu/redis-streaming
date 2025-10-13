package io.github.cuihairu.redis.streaming.cep;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class EventSequenceTest {

    @Test
    void testEmptySequence() {
        EventSequence<String> seq = new EventSequence<>();

        assertTrue(seq.isEmpty());
        assertEquals(0, seq.size());
        assertNull(seq.getFirst());
        assertNull(seq.getLast());
    }

    @Test
    void testAddEvent() {
        EventSequence<String> seq = new EventSequence<>();

        seq.addEvent("event1");
        assertEquals(1, seq.size());
        assertEquals("event1", seq.getFirst());
        assertEquals("event1", seq.getLast());

        seq.addEvent("event2");
        assertEquals(2, seq.size());
        assertEquals("event1", seq.getFirst());
        assertEquals("event2", seq.getLast());
    }

    @Test
    void testConstructorWithList() {
        EventSequence<Integer> seq = new EventSequence<>(Arrays.asList(1, 2, 3));

        assertEquals(3, seq.size());
        assertEquals(1, seq.getFirst());
        assertEquals(3, seq.getLast());
    }

    @Test
    void testConstructorWithTimestamps() {
        long start = 1000;
        long end = 2000;
        EventSequence<String> seq = new EventSequence<>(
                Arrays.asList("a", "b"),
                start,
                end
        );

        assertEquals(start, seq.getStartTime());
        assertEquals(end, seq.getEndTime());
        assertEquals(1000, seq.getDuration());
    }

    @Test
    void testGetEventsCopy() {
        EventSequence<Integer> seq = new EventSequence<>(Arrays.asList(1, 2, 3));

        var copy = seq.getEventsCopy();
        assertEquals(3, copy.size());

        // Modifying copy should not affect original
        copy.add(4);
        assertEquals(3, seq.size());
    }

    @Test
    void testToString() {
        EventSequence<String> seq = new EventSequence<>(
                Arrays.asList("a", "b"),
                1000,
                2000
        );

        String str = seq.toString();
        assertTrue(str.contains("size=2"));
        assertTrue(str.contains("duration=1000"));
    }
}
