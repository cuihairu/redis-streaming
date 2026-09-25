package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code DeadLetterQueue#add} finally-block branch that runs when the
 * backing queue rejects the offer: the size counter must not drift upward.
 */
class DeadLetterQueueOfferFailureCoverageTest {

    @Test
    void offerFailureKeepsSizeCounterConsistent() throws Exception {
        DeadLetterQueue<String> queue = new DeadLetterQueue<>();

        ConcurrentLinkedQueue<FailedElement<String>> rejecting =
                new ConcurrentLinkedQueue<>() {
                    @Override
                    public boolean offer(FailedElement<String> e) {
                        return false;
                    }
                };
        Field queueField = DeadLetterQueue.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        queueField.set(queue, rejecting);

        boolean added = queue.add("x", new IllegalStateException("boom"), 1);

        assertFalse(added, "a rejected offer must surface as a failed add");
        assertEquals(0, queue.size(), "size counter must be rolled back when the offer fails");
        assertTrue(queue.isEmpty());
    }
}
