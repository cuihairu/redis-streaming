package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeadLetterQueueTest {

    private DeadLetterQueue<String> dlq;

    @BeforeEach
    void setUp() {
        dlq = new DeadLetterQueue<>();
    }

    @Test
    void testAddAndPoll() {
        RuntimeException exception = new RuntimeException("Test error");
        dlq.add("element1", exception, 1);

        FailedElement<String> failed = dlq.poll();
        assertNotNull(failed);
        assertEquals("element1", failed.getElement());
        assertEquals("Test error", failed.getErrorMessage());
        assertEquals(1, failed.getAttemptCount());
    }

    @Test
    void testPeek() {
        dlq.add("element1", new RuntimeException("Error"), 1);

        FailedElement<String> peeked1 = dlq.peek();
        FailedElement<String> peeked2 = dlq.peek();

        assertNotNull(peeked1);
        assertSame(peeked1, peeked2); // Should be the same object
        assertEquals(1, dlq.size());
    }

    @Test
    void testSize() {
        assertEquals(0, dlq.size());

        dlq.add("elem1", new RuntimeException(), 1);
        assertEquals(1, dlq.size());

        dlq.add("elem2", new RuntimeException(), 1);
        assertEquals(2, dlq.size());

        dlq.poll();
        assertEquals(1, dlq.size());
    }

    @Test
    void testIsEmpty() {
        assertTrue(dlq.isEmpty());

        dlq.add("elem", new RuntimeException(), 1);
        assertFalse(dlq.isEmpty());

        dlq.poll();
        assertTrue(dlq.isEmpty());
    }

    @Test
    void testMaxSize() {
        DeadLetterQueue<String> limitedDlq = new DeadLetterQueue<>(2);

        assertTrue(limitedDlq.add("elem1", new RuntimeException(), 1));
        assertTrue(limitedDlq.add("elem2", new RuntimeException(), 1));
        assertFalse(limitedDlq.add("elem3", new RuntimeException(), 1)); // Should fail

        assertEquals(2, limitedDlq.size());
        assertEquals(2, limitedDlq.getMaxSize());
    }

    @Test
    void testIsFull() {
        DeadLetterQueue<String> limitedDlq = new DeadLetterQueue<>(2);

        assertFalse(limitedDlq.isFull());

        limitedDlq.add("elem1", new RuntimeException(), 1);
        assertFalse(limitedDlq.isFull());

        limitedDlq.add("elem2", new RuntimeException(), 1);
        assertTrue(limitedDlq.isFull());
    }

    @Test
    void testGetAll() {
        dlq.add("elem1", new RuntimeException("Error 1"), 1);
        dlq.add("elem2", new RuntimeException("Error 2"), 2);
        dlq.add("elem3", new RuntimeException("Error 3"), 3);

        List<FailedElement<String>> all = dlq.getAll();
        assertEquals(3, all.size());
        assertEquals("elem1", all.get(0).getElement());
        assertEquals("elem2", all.get(1).getElement());
        assertEquals("elem3", all.get(2).getElement());

        // Original queue should still have elements
        assertEquals(3, dlq.size());
    }

    @Test
    void testClear() {
        dlq.add("elem1", new RuntimeException(), 1);
        dlq.add("elem2", new RuntimeException(), 1);

        assertEquals(2, dlq.size());

        dlq.clear();

        assertEquals(0, dlq.size());
        assertTrue(dlq.isEmpty());
    }

    @Test
    void testPollEmptyQueue() {
        assertNull(dlq.poll());
    }

    @Test
    void testPeekEmptyQueue() {
        assertNull(dlq.peek());
    }

    @Test
    void testMultiplePolls() {
        dlq.add("elem1", new RuntimeException(), 1);
        dlq.add("elem2", new RuntimeException(), 2);

        FailedElement<String> first = dlq.poll();
        FailedElement<String> second = dlq.poll();

        assertEquals("elem1", first.getElement());
        assertEquals("elem2", second.getElement());
        assertTrue(dlq.isEmpty());
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        DeadLetterQueue<Integer> concurrentDlq = new DeadLetterQueue<>();
        int threadCount = 10;
        int itemsPerThread = 100;

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < itemsPerThread; j++) {
                    concurrentDlq.add(threadId * 1000 + j, new RuntimeException(), 1);
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(threadCount * itemsPerThread, concurrentDlq.size());
    }
}
