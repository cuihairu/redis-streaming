package io.github.cuihairu.redis.streaming.mq.dlq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Residual branch coverage for {@link RedisDeadLetterConsumer#loop()}: null read results,
 * codec fallbacks (with and without the readAllIds seam), hold-before-handle, RETRY
 * visibility re-add combinations and the outer error back-off interrupt.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisDeadLetterConsumerLoopGapTest {

    private RedissonClient client;
    private RStream<String, Object> defaultStream;
    private RStream<String, Object> stringStream;
    private RStream<String, Object> partitionStream;
    private final java.util.List<RedisDeadLetterConsumer> spawned = new java.util.ArrayList<>();

    private static final String TOPIC = "loop-gap";
    private static final String DLQ_KEY = "stream:topic:" + TOPIC + ":dlq";
    private static final String PART_KEY = "stream:topic:orig:p:0";

    @BeforeEach
    void setUp() throws Exception {
        DlqKeys.configure("stream:topic");
        client = mock(RedissonClient.class);
        defaultStream = mock(RStream.class);
        stringStream = mock(RStream.class);
        partitionStream = mock(RStream.class);
        when(client.getStream(org.mockito.ArgumentMatchers.eq(DLQ_KEY))).thenReturn((RStream) defaultStream);
        when(client.getStream(org.mockito.ArgumentMatchers.eq(DLQ_KEY), any(Codec.class))).thenReturn((RStream) stringStream);
        when(client.getStream(org.mockito.ArgumentMatchers.eq(PART_KEY), any(Codec.class))).thenReturn((RStream) partitionStream);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (RedisDeadLetterConsumer c : spawned) {
            try {
                c.stop();
                Field running = RedisDeadLetterConsumer.class.getDeclaredField("running");
                running.setAccessible(true);
                ((AtomicBoolean) running.get(c)).set(false);
                c.close();
            } catch (Throwable ignore) {
            }
        }
        spawned.clear();
        System.clearProperty("mq.dlq.test.readAllIds");
        System.clearProperty("mq.dlq.test.holdBeforeHandleMs");
        Thread.interrupted();
        org.mockito.Mockito.reset(client, defaultStream, stringStream, partitionStream);
    }

    private RedisDeadLetterConsumer spawn(String name) {
        RedisDeadLetterConsumer c = new RedisDeadLetterConsumer(client, name, "dlq-group");
        spawned.add(c);
        return c;
    }

    private static Map<StreamMessageId, Map<String, Object>> entry(Object payload) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("originalTopic", "orig");
        data.put("partitionId", "0");
        data.put("payload", payload);
        data.put("retryCount", "0");
        data.put("maxRetries", "3");
        Map<StreamMessageId, Map<String, Object>> out = new LinkedHashMap<>();
        out.put(new StreamMessageId(7, 0), data);
        return out;
    }

    private static Map<StreamMessageId, Map<String, Object>> empty() {
        return new LinkedHashMap<>();
    }

    /** Yields {@code first} exactly once, then idle reads that keep the loop calm. */
    private static java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> onceThenIdle(
            Map<StreamMessageId, Map<String, Object>> first) {
        AtomicBoolean used = new AtomicBoolean(false);
        return () -> {
            if (used.compareAndSet(false, true)) {
                return first;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return empty();
        };
    }

    // ===== null read results drive the codec fallbacks =====

    @Test
    void nullDefaultReadFallsBackToStringCodecAndAcks() throws Exception {
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(25);
                    return null;
                });
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> once = onceThenIdle(entry("p"));
        when(stringStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> once.get());

        RedisDeadLetterConsumer consumer = spawn("gap-1");
        CountDownLatch done = new CountDownLatch(1);
        consumer.subscribe(TOPIC, "g", e -> {
            done.countDown();
            return DeadLetterConsumer.HandleResult.SUCCESS;
        });
        consumer.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(100);
        verify(stringStream, atLeastOnce()).ack("g", new StreamMessageId(7, 0));
        consumer.stop();
        consumer.close();
    }

    @Test
    void nullReadsRouteThroughReadAllIdsStringFallback() throws Exception {
        System.setProperty("mq.dlq.test.readAllIds", "true");
        final AtomicInteger calls = new AtomicInteger();
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(25);
                    return null;
                });
        when(stringStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    if (calls.incrementAndGet() <= 2) {
                        return null;
                    }
                    if (calls.get() == 3) {
                        return entry("p");
                    }
                    Thread.sleep(25);
                    return empty();
                });

        RedisDeadLetterConsumer consumer = spawn("gap-2");
        CountDownLatch done = new CountDownLatch(1);
        consumer.subscribe(TOPIC, "g", e -> {
            done.countDown();
            return DeadLetterConsumer.HandleResult.SUCCESS;
        });
        consumer.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(100);
        consumer.stop();
        consumer.close();
    }

    @Test
    void allNullReadsKeepLoopCalmAndLogGuardHolds() throws Exception {
        System.setProperty("mq.dlq.test.readAllIds", "true");
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(25);
                    return null;
                });
        when(stringStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(25);
                    return null;
                });

        RedisDeadLetterConsumer consumer = spawn("gap-3");
        AtomicBoolean handled = new AtomicBoolean(false);
        consumer.subscribe(TOPIC, "g", e -> {
            handled.set(true);
            return DeadLetterConsumer.HandleResult.SUCCESS;
        });
        consumer.start();
        Thread.sleep(300);
        consumer.stop();
        consumer.close();
        assertFalse(handled.get(), "no entries means no handler invocations");
    }

    // ===== hold-before-handle honours the test seam =====

    @Test
    void holdBeforeHandleDelaysHandlerInvocation() throws Exception {
        System.setProperty("mq.dlq.test.holdBeforeHandleMs", "200");
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> once = onceThenIdle(entry("p"));
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> once.get());

        RedisDeadLetterConsumer consumer = spawn("gap-4");
        CountDownLatch done = new CountDownLatch(1);
        long[] startHolder = new long[1];
        consumer.subscribe(TOPIC, "g", e -> {
            startHolder[0] = System.nanoTime();
            done.countDown();
            return DeadLetterConsumer.HandleResult.SUCCESS;
        });
        long beforeStart = System.nanoTime();
        consumer.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        long elapsedMs = (startHolder[0] - beforeStart) / 1_000_000;
        assertTrue(elapsedMs >= 150, "handler must be held back by mq.dlq.test.holdBeforeHandleMs, waited " + elapsedMs + "ms");
        consumer.stop();
        consumer.close();
    }

    // ===== RETRY visibility re-add combinations =====

    @Test
    void retryVisibleCheckUsesSizeWhenStreamExistsButEmpty() throws Exception {
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> once = onceThenIdle(entry("p"));
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> once.get());
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 0));
        when(partitionStream.isExists()).thenReturn(true);
        when(partitionStream.size()).thenReturn(0L);

        RedisDeadLetterConsumer consumer = spawn("gap-5");
        CountDownLatch done = new CountDownLatch(1);
        consumer.subscribe(TOPIC, "g", e -> {
            done.countDown();
            return DeadLetterConsumer.HandleResult.RETRY;
        });
        consumer.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(200);
        verify(partitionStream, org.mockito.Mockito.times(2)).add(any());
        consumer.stop();
        consumer.close();
    }

    @Test
    void retryVisibilityCheckFailureIsSwallowed() throws Exception {
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> once = onceThenIdle(entry("p"));
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> once.get());
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 0));
        doThrow(new IllegalStateException("exists boom")).when(partitionStream).isExists();

        RedisDeadLetterConsumer consumer = spawn("gap-6");
        CountDownLatch done = new CountDownLatch(1);
        consumer.subscribe(TOPIC, "g", e -> {
            done.countDown();
            return DeadLetterConsumer.HandleResult.RETRY;
        });
        consumer.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(200);
        verify(partitionStream, org.mockito.Mockito.times(1)).add(any());
        consumer.stop();
        consumer.close();
    }

    // ===== outer error back-off is interruptible =====

    @Test
    void outerErrorBackoffSleepIsInterruptible() throws Exception {
        AtomicBoolean subscribed = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<Thread> loopHolder = new java.util.concurrent.atomic.AtomicReference<>();
        when(client.getStream(anyString())).thenAnswer(inv -> {
            if (subscribed.get()) {
                loopHolder.set(Thread.currentThread());
            }
            throw new IllegalStateException("stream boom");
        });
        when(client.getStream(anyString(), any(Codec.class))).thenThrow(new IllegalStateException("stream boom"));

        RedisDeadLetterConsumer consumer = spawn("gap-7");
        consumer.subscribe(TOPIC, "g", e -> DeadLetterConsumer.HandleResult.SUCCESS);
        subscribed.set(true);
        consumer.start();

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (loopHolder.get() == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        Thread loopThread = loopHolder.get();
        assertNotNull(loopThread, "loop thread must identify itself through the failing read");
        // interrupt repeatedly, each time only while the thread is inside the 200ms error back-off
        for (int attempt = 0; attempt < 5 && inLoop(loopThread); attempt++) {
            while (loopThread.getState() != Thread.State.TIMED_WAITING && inLoop(loopThread)
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            if (loopThread.getState() == Thread.State.TIMED_WAITING) {
                loopThread.interrupt();
            }
            Thread.sleep(50);
        }
        assertFalse(inLoop(loopThread), "interrupt must break the loop");
        assertTrue(consumer.isRunning(), "the consumer stays up; only the loop exits on interrupt");
        consumer.stop();
        consumer.close();
    }

    private static boolean inLoop(Thread t) {
        for (StackTraceElement el : t.getStackTrace()) {
            if (el.getClassName().equals(RedisDeadLetterConsumer.class.getName())
                    && el.getMethodName().equals("loop")) {
                return true;
            }
        }
        return false;
    }
}
