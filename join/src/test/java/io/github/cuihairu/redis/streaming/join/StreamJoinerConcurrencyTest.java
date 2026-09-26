package io.github.cuihairu.redis.streaming.join;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for B-16: the process/clear/buffer-size methods used to run on
 * bare {@link java.util.concurrent.ConcurrentHashMap} state with unsynchronized
 * {@link java.util.ArrayList} values, so concurrent {@code processLeft}/{@code processRight}
 * calls raced on the per-key list (lost inserts) and the cleanup/size iteration raced with
 * mutation (ConcurrentModificationException). All mutating and reading entry points are now
 * {@code synchronized}, which these tests pin down.
 */
class StreamJoinerConcurrencyTest {

    private static final long BASE_TS = 100_000L;

    record L(String key, long ts) {
    }

    record R(String key, long ts) {
    }

    /** Wide symmetric window + huge state cap: window filtering and eviction are not under test. */
    private static StreamJoiner<L, R, String, String> joiner() {
        JoinConfig<L, R, String> config = JoinConfig.<L, R, String>builder()
                .joinType(JoinType.INNER)
                .joinWindow(JoinWindow.ofSize(Duration.ofSeconds(60)))
                .leftKeySelector(L::key)
                .rightKeySelector(R::key)
                .leftTimestampExtractor(L::ts)
                .rightTimestampExtractor(R::ts)
                .maxStateSize(1_000_000)
                .build();
        return new StreamJoiner<>(config, (l, r) -> l.key() + "#" + r.key());
    }

    private interface ConcurrentTask {
        void run(int threadIdx) throws Exception;
    }

    /** Runs {@code threads} tasks released simultaneously by a barrier; rethrows any worker failure. */
    private static void runConcurrently(int threads, ConcurrentTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    task.run(id);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameKeyProcessLeftBuffersEveryElement() throws Exception {
        int threads = 16;
        int perThread = 250;
        StreamJoiner<L, R, String, String> joiner = joiner();

        runConcurrently(threads, t -> {
            for (int i = 0; i < perThread; i++) {
                joiner.processLeft(new L("k", BASE_TS + (long) t * perThread + i));
            }
        });

        assertEquals(threads * perThread, joiner.getLeftBufferSize(),
                "every buffered element must survive concurrent same-key inserts "
                        + "(old code: unsynchronized ArrayList.add lost elements under race)");
        assertEquals(0, joiner.getRightBufferSize());
    }

    @Test
    void concurrentLeftAndRightOnSameKeyProducesEveryPairExactlyOnce() throws Exception {
        int leftCount = 40;
        int rightCount = 40;
        StreamJoiner<L, R, String, String> joiner = joiner();
        AtomicLong matches = new AtomicLong();

        // Every element shares the key and lies inside the window, so each (left, right) pair
        // matches exactly once — evaluated by whichever of the two arrives second.
        runConcurrently(leftCount + rightCount, t -> {
            if (t < leftCount) {
                matches.addAndGet(joiner.processLeft(new L("k", BASE_TS + t)).size());
            } else {
                matches.addAndGet(joiner.processRight(new R("k", BASE_TS + t)).size());
            }
        });

        assertEquals((long) leftCount * rightCount, matches.get(),
                "every (left,right) pair must match exactly once across concurrent processing");
        assertEquals(leftCount, joiner.getLeftBufferSize());
        assertEquals(rightCount, joiner.getRightBufferSize());
    }

    @Test
    void bufferReadsDuringProcessingStayWithinBounds() throws Exception {
        int threads = 8;
        int perThread = 200;
        int total = threads * perThread;
        StreamJoiner<L, R, String, String> joiner = joiner();
        AtomicBoolean writersDone = new AtomicBoolean(false);
        AtomicLong reads = new AtomicLong();
        List<Throwable> readerErrors = java.util.Collections.synchronizedList(new ArrayList<>());

        Thread[] readers = new Thread[4];
        for (int r = 0; r < readers.length; r++) {
            readers[r] = new Thread(() -> {
                try {
                    while (!writersDone.get()) {
                        int size = joiner.getLeftBufferSize() + joiner.getRightBufferSize();
                        if (size < 0 || size > total) {
                            readerErrors.add(new AssertionError("buffer size out of range: " + size));
                            return;
                        }
                        reads.incrementAndGet();
                    }
                } catch (Throwable t) {
                    readerErrors.add(t);
                }
            });
            readers[r].start();
        }

        runConcurrently(threads, t -> {
            for (int i = 0; i < perThread; i++) {
                joiner.processLeft(new L("k", BASE_TS + (long) t * perThread + i));
            }
        });
        writersDone.set(true);
        for (Thread reader : readers) {
            reader.join(30_000);
        }

        assertTrue(readerErrors.isEmpty(), "reader threads must observe consistent sizes: " + readerErrors);
        assertTrue(reads.get() > 0, "readers must have performed observations");
        assertEquals(total, joiner.getLeftBufferSize(),
                "all inserts must be visible once writers finish (old code: lost updates)");
    }

    @Test
    void concurrentClearDoesNotCorruptProcessing() throws Exception {
        int threads = 8;
        int perThread = 150;
        int totalFed = threads * perThread * 2;
        StreamJoiner<L, R, String, String> joiner = joiner();
        AtomicBoolean stopClearing = new AtomicBoolean(false);
        List<Throwable> clearerErrors = java.util.Collections.synchronizedList(new ArrayList<>());

        Thread clearer = new Thread(() -> {
            while (!stopClearing.get()) {
                try {
                    joiner.clear();
                } catch (Throwable t) {
                    clearerErrors.add(t);
                    return;
                }
            }
        });
        clearer.start();

        runConcurrently(threads, t -> {
            for (int i = 0; i < perThread; i++) {
                joiner.processLeft(new L("k", BASE_TS + (long) t * perThread + i));
                joiner.processRight(new R("k", BASE_TS + (long) t * perThread + i));
            }
        });
        stopClearing.set(true);
        clearer.join(30_000);

        assertTrue(clearerErrors.isEmpty(), "clear() must not fail while processing: " + clearerErrors);
        int buffered = joiner.getLeftBufferSize() + joiner.getRightBufferSize();
        assertTrue(buffered >= 0 && buffered <= totalFed,
                "buffered count must stay within [0, fed] regardless of clear timing, was " + buffered);

        joiner.clear();
        assertEquals(0, joiner.getLeftBufferSize());
        assertEquals(0, joiner.getRightBufferSize());
    }
}
