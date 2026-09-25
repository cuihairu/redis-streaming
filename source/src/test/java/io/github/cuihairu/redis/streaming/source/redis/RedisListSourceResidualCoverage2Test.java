package io.github.cuihairu.redis.streaming.source.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the remaining {@link RedisListSource} branches: entries that vanish between the
 * emptiness check and the pop (concurrent consumers), the idle-sleep and interrupt paths of the
 * consume loop, the stopped guard of the poll loop and the shutdown fallbacks in {@code close()}.
 */
@Timeout(30)
class RedisListSourceResidualCoverage2Test {

    private static final String LIST = "c100d-residual-list";

    static class Fixture {
        final RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        final RList<String> list = mock(RList.class);
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final AtomicReference<Runnable> executed = new AtomicReference<>();
        final AtomicReference<Runnable> scheduled = new AtomicReference<>();
        final RedisListSource<String> source;

        Fixture() {
            when(redisson.<String>getList(LIST)).thenReturn(list);
            when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                    .thenAnswer(inv -> {
                        scheduled.set(inv.getArgument(0));
                        return null;
                    });
            doAnswer(inv -> {
                executed.set(inv.getArgument(0));
                return null;
            }).when(scheduler).execute(any(Runnable.class));
            try (MockedStatic<java.util.concurrent.Executors> executors =
                         mockStatic(java.util.concurrent.Executors.class)) {
                executors.when(() -> java.util.concurrent.Executors.newSingleThreadScheduledExecutor(any()))
                        .thenReturn(scheduler);
                source = new RedisListSource<>(redisson, LIST, String.class);
            }
        }
    }

    @Test
    void readBatchAndReadAllSkipEntriesVanishedUnderConcurrentPop() {
        Fixture f = new Fixture();
        when(f.list.isEmpty()).thenReturn(false, true);
        when(f.list.remove(0)).thenReturn(null);
        assertEquals(List.of(), f.source.readBatch(3));

        when(f.list.isEmpty()).thenReturn(false, true);
        assertEquals(List.of(), f.source.readAll());
    }

    @Test
    void consumeSleepsOnEmptyListAndStopsCleanly() throws Exception {
        Fixture f = new Fixture();
        when(f.list.remove(0)).thenThrow(new IndexOutOfBoundsException("empty"));

        AtomicInteger handled = new AtomicInteger();
        f.source.consume(v -> handled.incrementAndGet());
        Runnable task = f.executed.get();

        Thread worker = new Thread(task);
        worker.start();
        // let the loop run at least one idle sleep
        Thread.sleep(250);
        f.source.stop();
        worker.join(5000);

        assertFalse(worker.isAlive());
        assertEquals(0, handled.get());
    }

    @Test
    void consumeInterruptedExceptionEndsLoop() throws Exception {
        Fixture f = new Fixture();
        when(f.list.remove(0)).thenThrow(new IndexOutOfBoundsException("empty"));

        AtomicInteger handled = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        f.source.consume(v -> handled.incrementAndGet());
        Runnable task = f.executed.get();

        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        worker.start();

        assertTrue(done.await(5, TimeUnit.SECONDS), "interrupted sleep must break the loop");
        worker.join(2000);
        assertEquals(0, handled.get());
    }

    @Test
    void pollTaskReturnsImmediatelyWhenStopped() {
        Fixture f = new Fixture();
        AtomicInteger handled = new AtomicInteger();
        f.source.poll(v -> handled.incrementAndGet(), Duration.ofMillis(5));
        Runnable task = f.scheduled.get();

        f.source.stop();
        task.run();

        assertEquals(0, handled.get());
    }

    @Test
    void pollTaskSkipsMissingElementsAndDeliversValues() {
        Fixture f = new Fixture();
        AtomicInteger handled = new AtomicInteger();
        f.source.poll(v -> handled.incrementAndGet(), Duration.ofMillis(5));
        Runnable task = f.scheduled.get();

        when(f.list.remove(0)).thenThrow(new IndexOutOfBoundsException("empty"));
        task.run();
        assertEquals(0, handled.get());

        org.mockito.Mockito.doReturn("hello").when(f.list).remove(0);
        task.run();
        assertEquals(1, handled.get());
    }

    @Test
    void closeForcesShutdownWhenTerminationTimesOut() throws Exception {
        Fixture f = new Fixture();
        when(f.scheduler.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);

        f.source.close();

        verify(f.scheduler).shutdown();
        verify(f.scheduler).shutdownNow();
    }

    @Test
    void closeWithoutSchedulerIsNoOp() throws Exception {
        Fixture f = new Fixture();
        Field scheduler = RedisListSource.class.getDeclaredField("scheduler");
        scheduler.setAccessible(true);
        scheduler.set(f.source, null);

        f.source.close();
        f.source.close();
    }
}
