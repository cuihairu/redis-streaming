package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers {@code DeadLetterQueue#add} full-queue rejection and counter bookkeeping. */
class DeadLetterQueueAddCoverageTest {

    @Test
    void addRejectsWhenQueueIsFull() {
        DeadLetterQueue<String> queue = new DeadLetterQueue<>(2);
        assertTrue(queue.add("a", new IllegalStateException("x"), 1));
        assertTrue(queue.add("b", new IllegalStateException("y"), 2));
        assertFalse(queue.add("c", new IllegalStateException("z"), 3), "full queue rejects additions");
        assertEquals(2, queue.size());
        assertTrue(queue.isFull());
    }

    @Test
    void defaultConstructorAcceptsAdds() {
        DeadLetterQueue<String> queue = new DeadLetterQueue<>();
        assertTrue(queue.add("x", new RuntimeException("boom"), 1));
        assertEquals(1, queue.size());
        assertEquals("x", queue.poll().getElement());
        assertTrue(queue.isEmpty());
    }
}
