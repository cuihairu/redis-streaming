package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MQ-08 regression: a worker blocked in {@code inFlightLimiter.acquire()} when the
 * consumer stops must not release a permit it never acquired. The old code's acquire
 * returned void (it could not report failure) and the dispatch finally-block released
 * unconditionally, so every stop-during-backpressure race permanently raised the
 * effective maxInFlight ceiling. Uses only pre-fix public API + reflection, so it
 * reproduces on the old code.
 */
class RedisMessageConsumerInFlightBackpressureTest {

    private static final Class<?>[] PROCESS_INCOMING = {String.class, String.class, int.class,
            String.class, Map.class, RStream.class, MessageHandler.class, boolean.class};
    private static final Class<?>[] NONE = {};

    private RedissonClient client;
    private RStream<String, Object> dataStream;
    private RedisMessageConsumer consumer;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        client = mock(RedissonClient.class);
        dataStream = mock(RStream.class);
        when(client.getStream(any(String.class))).thenReturn((RStream) mock(RStream.class));
        when(client.getStream(any(String.class), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RStream) dataStream);
        when(client.getMap(any(String.class))).thenReturn((RMap) mock(RMap.class));
        when(client.getBucket(any(String.class), any(org.redisson.client.codec.Codec.class)))
                .thenReturn((RBucket) mock(RBucket.class));

        consumer = new RedisMessageConsumer(client, "unit-backpressure",
                mock(TopicPartitionRegistry.class), MqOptions.builder().maxInFlight(1).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        invoke("close", NONE);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = RedisMessageConsumer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        return invoke(consumer, name, types, args);
    }

    private AtomicBoolean running() throws Exception {
        Field f = RedisMessageConsumer.class.getDeclaredField("running");
        f.setAccessible(true);
        return (AtomicBoolean) f.get(consumer);
    }

    private Semaphore limiter() throws Exception {
        Field f = RedisMessageConsumer.class.getDeclaredField("inFlightLimiter");
        f.setAccessible(true);
        return (Semaphore) f.get(consumer);
    }

    private static Map<String, Object> data() {
        Map<String, Object> data = new HashMap<>();
        data.put("payload", "p");
        return data;
    }

    /** Wait until the thread is parked inside semaphore acquire. */
    private static void awaitBlocked(Thread t) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (t.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void stopWhileAWorkerIsBlockedInAcquireDoesNotExceedMaxInFlight() throws Exception {
        running().set(true);

        // first dispatch holds the only permit inside a blocked handler
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        MessageHandler blocking = msg -> {
            handlerEntered.countDown();
            try {
                releaseHandler.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return MessageHandleResult.SUCCESS;
        };
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                        "t", "g", 0, "5-0", data(), dataStream, blocking, false);
            } catch (Throwable e) {
                firstError.set(e);
            }
        });
        first.start();
        assertTrue(handlerEntered.await(10, TimeUnit.SECONDS), "first dispatch must take the permit");

        // second dispatch parks inside acquire (semaphore exhausted)
        AtomicReference<Object> acquired = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        MessageHandler fast = msg -> MessageHandleResult.SUCCESS;
        Thread second = new Thread(() -> {
            try {
                acquired.set(invoke(consumer, "processIncomingRecord", PROCESS_INCOMING,
                        "t", "g", 0, "5-1", data(), dataStream, fast, false));
            } catch (Throwable e) {
                secondError.set(e);
            }
        });
        second.start();
        awaitBlocked(second);
        assertTrue(second.getState() == Thread.State.WAITING,
                "second dispatch must be blocked in backpressure acquire, was " + second.getState());

        // the stop() race: the acquire loop exits without a permit
        running().set(false);
        second.interrupt();
        second.join(10_000);
        assertFalse(second.isAlive(), "blocked dispatch must return after stop");

        releaseHandler.countDown();
        first.join(10_000);
        assertFalse(first.isAlive());
        if (firstError.get() != null) {
            throw new AssertionError("first dispatch failed", firstError.get());
        }
        if (secondError.get() != null) {
            throw new AssertionError("second dispatch failed", secondError.get());
        }

        Semaphore semaphore = limiter();
        assertEquals(1, semaphore.availablePermits(),
                "a dispatch that never acquired a permit must not release one: "
                        + "available permits must stay at maxInFlight");
    }

    @Test
    void acquireReportsWhenItReturnsWithoutAPermit() throws Exception {
        running().set(true);

        // take the only permit on this thread, park a second acquire, then stop it
        assertEquals(Boolean.TRUE, invoke("acquireInFlightPermit", NONE),
                "sanity: the first acquire takes the single permit");

        AtomicReference<Object> acquired = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                acquired.set(invoke("acquireInFlightPermit", NONE));
            } catch (Throwable ignore) {
            }
        });
        second.start();
        awaitBlocked(second);
        assertTrue(second.getState() == Thread.State.WAITING,
                "second acquire must be parked in backpressure, was " + second.getState());

        running().set(false);
        second.interrupt();
        second.join(10_000);
        assertFalse(second.isAlive());

        assertEquals(Boolean.FALSE, acquired.get(),
                "acquire must report that it returned without a permit, "
                        + "so the caller can skip the paired release");
    }
}
