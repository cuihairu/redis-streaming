package io.github.cuihairu.redis.streaming.mq.dlq;

import io.github.cuihairu.redis.streaming.mq.metrics.MqMetrics;
import io.github.cuihairu.redis.streaming.mq.metrics.MqMetricsCollector;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives RedisDeadLetterConsumer#loop through short-lived start/stop flows with a mocked
 * Redisson client to cover residual loop branches (readAllIds fallbacks, hold-before-handle,
 * SUCCESS, RETRY visibility retry, replay failures, outer error sleep/interrupt).
 */
@SuppressWarnings({"unchecked", "deprecation"})
class RedisDeadLetterConsumerLoopResidualCoverageTest {

    private RedissonClient client;
    private RStream<String, Object> defaultStream;
    private RStream<String, Object> stringStream;
    private RStream<String, Object> partitionStream;
    private MqMetricsCollector previousCollector;
    private final java.util.List<RedisDeadLetterConsumer> spawned = new java.util.ArrayList<>();

    private static final String TOPIC = "loop-residual";
    private static final String DLQ_KEY = "stream:topic:" + TOPIC + ":dlq";
    private static final String PART_KEY = "stream:topic:orig:p:0";

    @BeforeEach
    void setUp() {
        previousCollector = MqMetrics.get();
        client = mock(RedissonClient.class);
        defaultStream = mock(RStream.class);
        stringStream = mock(RStream.class);
        partitionStream = mock(RStream.class);
        when(client.getStream(anyString())).thenReturn((RStream) defaultStream);
        when(client.getStream(anyString(), any(Codec.class))).thenReturn((RStream) stringStream);
        when(client.getStream(org.mockito.ArgumentMatchers.eq(DLQ_KEY), any(Codec.class))).thenReturn((RStream) stringStream);
        when(client.getStream(org.mockito.ArgumentMatchers.eq(PART_KEY), any(Codec.class))).thenReturn((RStream) partitionStream);
    }

    @AfterEach
    void tearDown() {
        // always stop/close every consumer created in the test, even on failure paths
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
        MqMetrics.setCollector(previousCollector);
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

    /** First call yields {@code once}; later calls sleep briefly and yield nothing (anti-spin). */
    private static java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> onceThenIdle(
            Map<StreamMessageId, Map<String, Object>> once) {
        AtomicBoolean first = new AtomicBoolean(true);
        return () -> {
            if (first.compareAndSet(true, false)) {
                return once;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return empty();
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = RedisDeadLetterConsumer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void drive(RedisDeadLetterConsumer consumer, CountDownLatch done, long timeoutMs) throws Exception {
        consumer.subscribe(TOPIC, "g", e -> {
            done.countDown();
            return DeadLetterConsumer.HandleResult.SUCCESS;
        });
        consumer.start();
        assertTrue(done.await(Math.max(timeoutMs, 15000), TimeUnit.MILLISECONDS));
        consumer.stop();
        consumer.close();
    }

    @Test
    void readAllIdsFallbackFindsStringEntriesAndHonoursHoldProperty() throws Exception {
        System.setProperty("mq.dlq.test.readAllIds", "true");
        System.setProperty("mq.dlq.test.holdBeforeHandleMs", "1");

        final AtomicInteger stringCalls = new AtomicInteger();
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(25);
                    return empty();
                });
        when(stringStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    if (stringCalls.incrementAndGet() == 1) {
                        return empty();
                    }
                    if (stringCalls.get() == 2) {
                        return entry("hello");
                    }
                    Thread.sleep(25);
                    return empty();
                });

        RedisDeadLetterConsumer consumer = spawn("loop-1");
        CountDownLatch done = new CountDownLatch(1);
        drive(consumer, done, 4000);
    }

    @Test
    void readAllIdsFallbackToleratesReadFailuresOnBothCodecs() throws Exception {
        System.setProperty("mq.dlq.test.readAllIds", "true");
        final AtomicInteger defaultCalls = new AtomicInteger();
        final AtomicInteger stringCalls = new AtomicInteger();
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    if (defaultCalls.incrementAndGet() == 1) {
                        return empty();
                    }
                    Thread.sleep(25);
                    throw new IllegalStateException("default read boom"); // covers 121 catch
                });
        when(stringStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> {
                    stringCalls.incrementAndGet();
                    Thread.sleep(25);
                    throw new IllegalStateException("string read boom"); // covers 127 catch
                });

        RedisDeadLetterConsumer consumer = spawn("loop-2");
        consumer.subscribe(TOPIC, "g", e -> DeadLetterConsumer.HandleResult.SUCCESS);
        consumer.start();
        Thread.sleep(200);
        consumer.stop();
        consumer.close();
    }

    @Test
    void retryWithoutReplayHandlerReAddsWhenNotVisible() throws Exception {
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> oncePayload1 = onceThenIdle(entry("payload-1"));
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> oncePayload1.get());
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 0));
        when(partitionStream.isExists()).thenReturn(false);

        RedisDeadLetterConsumer consumer = spawn("loop-3");
        CountDownLatch done = new CountDownLatch(1);
        consumer.subscribe(TOPIC, "g", e -> {
            done.countDown();
            return DeadLetterConsumer.HandleResult.RETRY;
        });
        consumer.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(200); // let the RETRY branch finish (visibility re-add + ack)
        consumer.stop();
        consumer.close();
    }

    @Test
    void retryReplayFailureIsLogged() throws Exception {
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> oncePayload2 = onceThenIdle(entry("payload-2"));
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> oncePayload2.get());
        when(partitionStream.add(any())).thenThrow(new IllegalStateException("replay boom"));

        RedisDeadLetterConsumer consumer = spawn("loop-4");
        CountDownLatch done = new CountDownLatch(1);
        consumer.subscribe(TOPIC, "g", e -> {
            done.countDown();
            return DeadLetterConsumer.HandleResult.RETRY;
        });
        consumer.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(150);
        consumer.stop();
        consumer.close();
    }

    @Test
    void retryMetricsFailureIsSwallowed() throws Exception {
        MqMetricsCollector throwing = new MqMetricsCollector() {
            @Override public void incProduced(String t, int p) {}
            @Override public void incConsumed(String t, int p) {}
            @Override public void incAcked(String t, int p) {}
            @Override public void incRetried(String t, int p) {}
            @Override public void incDeadLetter(String t, int p) {}
            @Override public void recordHandleLatency(String t, int p, long m) {}
            @Override public void recordDlqReplay(String t, int p, boolean s, long d) {
                throw new IllegalStateException("metrics boom");
            }
        };
        MqMetrics.setCollector(throwing);
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> oncePayload3 = onceThenIdle(entry("payload-3"));
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> oncePayload3.get());
        when(partitionStream.add(any())).thenReturn(new StreamMessageId(8, 1));
        when(partitionStream.isExists()).thenReturn(true);
        when(partitionStream.size()).thenReturn(1L);

        RedisDeadLetterConsumer consumer = spawn("loop-5");
        CountDownLatch done = new CountDownLatch(1);
        consumer.subscribe(TOPIC, "g", e -> {
            done.countDown();
            return DeadLetterConsumer.HandleResult.RETRY;
        });
        consumer.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(150);
        consumer.stop();
        consumer.close();
    }

    @Test
    void outerLoopErrorSleepIsInterruptible() throws Exception {
        when(client.getStream(anyString())).thenThrow(new IllegalStateException("stream boom"));

        RedisDeadLetterConsumer consumer = spawn("loop-6");
        consumer.subscribe(TOPIC, "g", e -> DeadLetterConsumer.HandleResult.SUCCESS);
        consumer.start();
        Thread.sleep(80); // let the loop enter its 200ms back-off sleep after the failure
        Field ex = RedisDeadLetterConsumer.class.getDeclaredField("executor");
        ex.setAccessible(true);
        ((ScheduledExecutorService) ex.get(consumer)).shutdownNow(); // interrupt -> sleep break
        Thread.sleep(200);
        consumer.stop();
        consumer.close();
    }

    @Test
    void closeWhileInterruptedCoversInterruptPath() throws Exception {
        RedisDeadLetterConsumer consumer = spawn("loop-7");
        // keep the executor busy so awaitTermination waits and then trips on the interrupt
        Field ex = RedisDeadLetterConsumer.class.getDeclaredField("executor");
        ex.setAccessible(true);
        ((ScheduledExecutorService) ex.get(consumer)).submit(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignore) {
            }
        });
        Thread.currentThread().interrupt();
        assertDoesNotThrow(consumer::close);
        assertTrue(Thread.interrupted());
    }

    @Test
    void failHandlerAcksAndNullPayloadEntryParses() throws Exception {
        Map<StreamMessageId, Map<String, Object>> e = entry(null);
        e.get(new StreamMessageId(7, 0)).put("headers", "{\"k\":\"v\"}");
        java.util.function.Supplier<Map<StreamMessageId, Map<String, Object>>> onceFail = onceThenIdle(e);
        when(defaultStream.readGroup(anyString(), anyString(), any(org.redisson.api.stream.StreamReadGroupArgs.class)))
                .thenAnswer(inv -> onceFail.get());

        RedisDeadLetterConsumer consumer = spawn("loop-8");
        CountDownLatch done = new CountDownLatch(1);
        consumer.subscribe(TOPIC, "g", entryObj -> {
            done.countDown();
            return DeadLetterConsumer.HandleResult.FAIL;
        });
        consumer.start();
        assertTrue(done.await(15000, TimeUnit.MILLISECONDS));
        Thread.sleep(100);
        consumer.stop();
        consumer.close();
    }
}
