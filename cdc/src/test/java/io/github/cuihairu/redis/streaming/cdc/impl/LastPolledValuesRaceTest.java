package io.github.cuihairu.redis.streaming.cdc.impl;

import io.github.cuihairu.redis.streaming.cdc.CDCConfiguration;
import io.github.cuihairu.redis.streaming.cdc.CDCConfigurationBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CDC-M2: {@code lastPolledValues} is a plain HashMap. It is written from the polling
 * scheduler thread AND from every user-thread {@code commit()}/{@code resetPosition()}
 * (doCommit/doResetToPosition), and read through the public {@code getLastPolledValues()}
 * snapshot from any thread. Concurrent HashMap puts resize-interleave: entries are lost
 * (a table silently restarts polling from an older watermark -> duplicates) or the
 * internal table is corrupted outright (NPE / AIOOBE inside put).
 *
 * The race is hammered through the pre-fix public surface only: N threads committing
 * distinct table positions while a reader takes snapshots; afterwards every entry must
 * be present with its exact value and no structural error may have occurred.
 */
class LastPolledValuesRaceTest {

    private static final int WRITERS = 8;
    private static final int PER_WRITER = 3_000;

    @Test
    void concurrentCommitsWithConcurrentSnapshotsMustNotLoseEntries() throws Exception {
        CDCConfiguration config = CDCConfigurationBuilder.forDatabasePolling("poll-m2")
                .username("u").password("p").build();
        DatabasePollingCDCConnector connector = new DatabasePollingCDCConnector(config);

        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean writersDone = new AtomicBoolean();
        AtomicInteger readErrors = new AtomicInteger();
        AtomicReference<Throwable> firstReadError = new AtomicReference<>();
        Map<Throwable, Boolean> writeErrorKinds = new ConcurrentHashMap<>();
        AtomicInteger writeErrors = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + 1);
        try {
            Future<?> reader = pool.submit(() -> {
                await(go);
                // Keep copying the live map while writers resize it — exactly what any
                // monitoring thread calling getLastPolledValues() does in production.
                while (!writersDone.get()) {
                    try {
                        connector.getLastPolledValues();
                    } catch (Throwable t) {
                        readErrors.incrementAndGet();
                        firstReadError.compareAndSet(null, t);
                    }
                }
            });

            CountDownLatch done = new CountDownLatch(WRITERS);
            for (int t = 0; t < WRITERS; t++) {
                final int tid = t;
                pool.submit(() -> {
                    await(go);
                    try {
                        for (int i = 0; i < PER_WRITER; i++) {
                            connector.doCommit("tbl_" + tid + "_" + i + ":v" + i);
                        }
                    } catch (Throwable err) {
                        writeErrors.incrementAndGet();
                        writeErrorKinds.put(err, Boolean.TRUE);
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "writers must finish");
            writersDone.set(true);
            reader.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> snap;
        try {
            snap = connector.getLastPolledValues();
        } catch (Throwable t) {
            throw new AssertionError("final snapshot corrupted by concurrent puts", t);
        }

        assertEquals(0, writeErrors.get(),
                "concurrent doCommit threw " + writeErrors + "x, kinds=" + writeErrorKinds.keySet());
        assertEquals(0, readErrors.get(),
                "concurrent snapshot copy threw " + readErrors + "x, first=" + firstReadError.get());
        assertEquals(WRITERS * PER_WRITER, snap.size(), "lost updates: entries silently vanished");
        for (int t = 0; t < WRITERS; t++) {
            Object v = snap.get("tbl_" + t + "_1234");
            if (v == null) {
                fail("entry tbl_" + t + "_1234 lost");
            }
            assertEquals("v1234", v, "value mismatch for tbl_" + t + "_1234");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
