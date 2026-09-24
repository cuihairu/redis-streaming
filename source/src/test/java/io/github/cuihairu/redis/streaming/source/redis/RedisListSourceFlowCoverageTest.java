package io.github.cuihairu.redis.streaming.source.redis;

import org.junit.jupiter.api.Test;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers RedisListSource consume/poll/pollBatch loop bodies and close(). */
class RedisListSourceFlowCoverageTest {

    @SuppressWarnings("unchecked")
    private RedissonClient redisson(RList<String> list) {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String>getList(anyString())).thenReturn(list);
        return redisson;
    }

    @Test
    void consumeDeliversElementThenStops() throws Exception {
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(list.remove(0)).thenReturn("first", (String) null);

        RedisListSource<String> source = new RedisListSource<>(redisson(list), "q", String.class);
        CountDownLatch seen = new CountDownLatch(1);
        try {
            source.consume(value -> {
                if ("first".equals(value)) {
                    seen.countDown();
                    source.stop();
                }
            });
            assertTrue(seen.await(5, TimeUnit.SECONDS), "consumer loop delivered the element");
        } finally {
            source.close();
        }
        assertFalse(source.isRunning());
    }

    @Test
    void consumeLoopSurvivesHandlerFailure() throws Exception {
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(list.remove(0)).thenReturn("boom", "done", (String) null);

        RedisListSource<String> source = new RedisListSource<>(redisson(list), "q", String.class);
        CountDownLatch seen = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        try {
            source.consume(value -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("handler bug");
                }
                seen.countDown();
                source.stop();
            });
            assertTrue(seen.await(5, TimeUnit.SECONDS), "loop continued after handler failure");
        } finally {
            source.close();
        }
    }

    @Test
    void pollDeliversItemsOnSchedule() throws Exception {
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(list.remove(anyInt())).thenReturn("a", (String) null);

        RedisListSource<String> source = new RedisListSource<>(redisson(list), "q", String.class);
        CountDownLatch seen = new CountDownLatch(1);
        try {
            source.poll(v -> seen.countDown(), Duration.ofMillis(50));
            assertTrue(seen.await(5, TimeUnit.SECONDS), "poll handler fired");
            source.stop();
        } finally {
            source.close();
        }
    }

    @Test
    void pollBatchDeliversNonEmptyBatches() throws Exception {
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(list.isEmpty()).thenReturn(false, true);
        when(list.remove(0)).thenReturn("b");

        RedisListSource<String> source = new RedisListSource<>(redisson(list), "q", String.class);
        CountDownLatch seen = new CountDownLatch(1);
        try {
            source.pollBatch(batch -> {
                if (!batch.isEmpty()) {
                    seen.countDown();
                }
            }, 5, Duration.ofMillis(50));
            assertTrue(seen.await(5, TimeUnit.SECONDS), "batch handler fired");
            source.stop();
        } finally {
            source.close();
        }
    }

    @Test
    void pollHandlersSurviveHandlerFailures() throws Exception {
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(list.remove(anyInt())).thenReturn("x");
        when(list.isEmpty()).thenReturn(false);

        RedisListSource<String> source = new RedisListSource<>(redisson(list), "q", String.class);
        try {
            source.poll(v -> {
                throw new IllegalStateException("handler bug");
            }, Duration.ofMillis(30));
            source.pollBatch(b -> {
                throw new IllegalStateException("handler bug");
            }, 5, Duration.ofMillis(30));
            Thread.sleep(150);
        } finally {
            source.close();
        }
    }

    @Test
    void closeStopsIdleConsumerQuickly() {
        @SuppressWarnings("unchecked")
        RList<String> list = mock(RList.class);
        when(list.remove(0)).thenThrow(new IndexOutOfBoundsException("empty"));

        RedisListSource<String> source = new RedisListSource<>(redisson(list), "q", String.class);
        source.consume(v -> { });
        assertDoesNotThrow(source::close);
    }
}
