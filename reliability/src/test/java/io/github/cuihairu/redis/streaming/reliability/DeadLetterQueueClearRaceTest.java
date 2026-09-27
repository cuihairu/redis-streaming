package io.github.cuihairu.redis.streaming.reliability;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B-35 regression: the pre-fix {@code clear()} did {@code queue.clear(); sizeCounter.set(0)},
 * which wiped the increments of adds landing between the two steps — the counter
 * permanently under-counted and the queue could exceed maxSize afterwards.
 */
class DeadLetterQueueClearRaceTest {

    @Test
    void ctorRejectsNonPositiveMaxSize() {
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterQueue<String>(0));
        assertThrows(IllegalArgumentException.class, () -> new DeadLetterQueue<String>(-1));
        // the old code accepted these and then silently discarded every failure
        DeadLetterQueue<String> dq = new DeadLetterQueue<>();
        assertTrue(dq.add("e", new RuntimeException("boom"), 1), "default queue accepts");
    }

    @Test
    void clearKeepsTheCounterConsistentUnderConcurrentAdds() throws Exception {
        DeadLetterQueue<Integer> dq = new DeadLetterQueue<>(64);

        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);

        // two adders hammering the queue while the main thread clears in a loop
        for (int t = 0; t < 2; t++) {
            final int base = t * 1_000_000;
            pool.submit(() -> {
                try {
                    start.await();
                    int i = 0;
                    while (running.get()) {
                        dq.add(base + i, new RuntimeException("boom"), 1);
                        i++;
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // one clearer: every add landing inside the old clear's two-step window drifts the counter
        pool.submit(() -> {
            try {
                start.await();
                while (running.get()) {
                    dq.clear();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        Thread.sleep(400);
        running.set(false);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // the invariant the old code broke: the counter mirrors the queued elements,
        // and the queue never exceeds maxSize
        assertEquals(dq.getAll().size(), dq.size(),
                "size() must mirror the queued elements (old code's bulk reset lost increments)");
        assertTrue(dq.getAll().size() <= dq.getMaxSize(),
                "the queue must never hold more than maxSize elements");
    }

    @Test
    void counterStaysConsistentWhenAddPollAndClearAllInterleave() throws Exception {
        DeadLetterQueue<Integer> dq = new DeadLetterQueue<>(64);

        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);

        // the queue's three mutators racing at once: add, poll and clear each remove or
        // insert under their own lock-free path — every removal must carry exactly one
        // counter decrement for the invariant to survive
        pool.submit(() -> {
            try {
                start.await();
                int i = 0;
                while (running.get()) {
                    dq.add(i++, new RuntimeException("boom"), 1);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                while (running.get()) {
                    dq.poll();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                while (running.get()) {
                    dq.clear();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        Thread.sleep(400);
        running.set(false);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(dq.getAll().size(), dq.size(),
                "counter must mirror the queued elements under three-way interleaving");
        assertTrue(dq.getAll().size() <= dq.getMaxSize(),
                "the queue must never hold more than maxSize elements");
    }
}
