package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Covers pure helper methods of {@link RedisMessageConsumer} (parseStreamId, compareStreamId,
 * isPayloadMissing, PartitionKey value semantics, in-flight permits) via reflection without Redis.
 */
class RedisMessageConsumerPrivateMethodCoverageTest {

    private RedisMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RedisMessageConsumer(mock(org.redisson.api.RedissonClient.class), "unit-c",
                mock(TopicPartitionRegistry.class), MqOptions.builder().maxInFlight(2).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        closeQuietly(consumer);
    }

    private static void closeQuietly(RedisMessageConsumer c) {
        try {
            Method m = RedisMessageConsumer.class.getMethod("close");
            m.invoke(c);
        } catch (Exception ignore) {
        }
    }

    private static Object invokePrivate(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = RedisMessageConsumer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Object newPartitionKey(String topic, String group, int pid) throws Exception {
        Class<?> clazz = Class.forName(RedisMessageConsumer.class.getName() + "$PartitionKey");
        Constructor<?> ctor = clazz.getDeclaredConstructor(String.class, String.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(topic, group, pid);
    }

    // ===== parseStreamId =====

    @Test
    void parseStreamIdHandlesAllForms() throws Exception {
        Class<?>[] sig = {String.class};
        assertEquals(new StreamMessageId(123, 5), invokePrivate(consumer, "parseStreamId", sig, "123-5"));
        assertEquals(new StreamMessageId(42), invokePrivate(consumer, "parseStreamId", sig, "42"));
        assertEquals(StreamMessageId.MIN, invokePrivate(consumer, "parseStreamId", sig, (Object) null));
        assertEquals(StreamMessageId.MIN, invokePrivate(consumer, "parseStreamId", sig, "not-a-number"));
        assertEquals(StreamMessageId.MIN, invokePrivate(consumer, "parseStreamId", sig, "1-2-3"));
        assertEquals(StreamMessageId.MIN, invokePrivate(consumer, "parseStreamId", sig, ""));
    }

    // ===== compareStreamId =====

    @Test
    void compareStreamIdCoversAllBranches() throws Exception {
        Class<?>[] sig = {String.class, String.class};
        assertEquals(-1, invokePrivate(consumer, "compareStreamId", sig, "1-0", "2-0"));
        assertEquals(1, invokePrivate(consumer, "compareStreamId", sig, "2-0", "1-0"));
        assertEquals(-1, invokePrivate(consumer, "compareStreamId", sig, "5-1", "5-2"));
        assertEquals(1, invokePrivate(consumer, "compareStreamId", sig, "5-2", "5-1"));
        assertEquals(0, invokePrivate(consumer, "compareStreamId", sig, "5-2", "5-2"));
        assertEquals(1, invokePrivate(consumer, "compareStreamId", sig, "5-2", "5"));
        assertEquals(-1, invokePrivate(consumer, "compareStreamId", sig, "5", "5-2"));
        assertEquals(0, invokePrivate(consumer, "compareStreamId", sig, "7", "7"));
        // exception fallback: lexicographic compare
        assertEquals("a".compareTo("b"), invokePrivate(consumer, "compareStreamId", sig, "a", "b"));
    }

    // ===== isPayloadMissing =====

    @Test
    void isPayloadMissingMatchesMessagesCausesAndNull() throws Exception {
        Class<?>[] sig = {Throwable.class};
        assertFalse((Boolean) invokePrivate(consumer, "isPayloadMissing", sig, (Object) null));
        assertFalse((Boolean) invokePrivate(consumer, "isPayloadMissing", sig, new RuntimeException("boom")));
        assertTrue((Boolean) invokePrivate(consumer, "isPayloadMissing", sig, new RuntimeException("Payload not found: x")));
        assertTrue((Boolean) invokePrivate(consumer, "isPayloadMissing", sig, new RuntimeException("Failed to load payload: x")));
        assertTrue((Boolean) invokePrivate(consumer, "isPayloadMissing", sig, new RuntimeException("Payload not found in hash: x")));
        assertTrue((Boolean) invokePrivate(consumer, "isPayloadMissing", sig, new RuntimeException("Failed to load payload from hash: x")));
        // null message but payload-missing cause (nested)
        RuntimeException nested = new RuntimeException("Payload not found: y");
        RuntimeException wrapper = new RuntimeException(null, new IllegalStateException(null, nested));
        assertTrue((Boolean) invokePrivate(consumer, "isPayloadMissing", sig, wrapper));
        // null message with unrelated cause
        RuntimeException other = new RuntimeException(null, new IllegalStateException("other"));
        assertFalse((Boolean) invokePrivate(consumer, "isPayloadMissing", sig, other));
    }

    // ===== PartitionKey value semantics =====

    @Test
    void partitionKeyEqualsHashCodeAndToString() throws Exception {
        Object a = newPartitionKey("t", "g", 1);
        Object same = newPartitionKey("t", "g", 1);
        Object otherTopic = newPartitionKey("t2", "g", 1);
        Object otherGroup = newPartitionKey("t", "g2", 1);
        Object otherPid = newPartitionKey("t", "g", 2);

        Method equals = a.getClass().getDeclaredMethod("equals", Object.class);
        Method hashCode = a.getClass().getDeclaredMethod("hashCode");
        Method toString = a.getClass().getDeclaredMethod("toString");
        equals.setAccessible(true);
        hashCode.setAccessible(true);
        toString.setAccessible(true);

        assertTrue((Boolean) equals.invoke(a, a));
        assertTrue((Boolean) equals.invoke(a, same));
        assertEquals(hashCode.invoke(a), hashCode.invoke(same));
        assertFalse((Boolean) equals.invoke(a, otherTopic));
        assertFalse((Boolean) equals.invoke(a, otherGroup));
        assertFalse((Boolean) equals.invoke(a, otherPid));
        assertFalse((Boolean) equals.invoke(a, "not-a-key"));
        assertEquals("t:g:p1", toString.invoke(a));
    }

    // ===== in-flight permits =====

    @Test
    void acquireAndReleasePermitsBlockUntilReleased() throws Exception {
        Field running = RedisMessageConsumer.class.getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(consumer)).set(true);

        Class<?>[] none = {};
        // take the only two permits so the next acquire blocks
        invokePrivate(consumer, "acquireInFlightPermit", none);
        invokePrivate(consumer, "acquireInFlightPermit", none);

        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                entered.countDown();
                invokePrivate(consumer, "acquireInFlightPermit", none);
            } catch (Throwable e) {
                err.set(e);
            }
        });
        t.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread.sleep(80); // let the third acquire block so wait time is non-zero
        invokePrivate(consumer, "releaseInFlightPermit", none);
        t.join(3000);
        assertFalse(t.isAlive(), "blocked acquire should complete after release");
        assertNull(err.get());

        invokePrivate(consumer, "releaseInFlightPermit", none);
        invokePrivate(consumer, "releaseInFlightPermit", none);
    }

    @Test
    void acquirePermitInterruptedWhileStoppingRestoresInterruptFlag() throws Exception {
        Field running = RedisMessageConsumer.class.getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(consumer)).set(true);

        Class<?>[] none = {};
        // occupy both permits so the next acquire blocks
        invokePrivate(consumer, "acquireInFlightPermit", none);
        invokePrivate(consumer, "acquireInFlightPermit", none);

        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                entered.countDown();
                invokePrivate(consumer, "acquireInFlightPermit", none);
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                err.set(e);
            }
        });
        t.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);
        ((AtomicBoolean) running.get(consumer)).set(false); // loop must exit after the interrupt
        t.interrupt();
        t.join(3000);
        assertFalse(t.isAlive(), "interrupted acquire should return");
        assertNull(err.get());
        assertTrue(interruptedAfter.get(), "interrupt flag should be restored");
        invokePrivate(consumer, "releaseInFlightPermit", none);
        invokePrivate(consumer, "releaseInFlightPermit", none);
    }

    @Test
    void fiveArgConstructorWithNullOptionsAndBroker() throws Exception {
        RedisMessageConsumer c = new RedisMessageConsumer(
                mock(org.redisson.api.RedissonClient.class), "unit-null-opts",
                mock(TopicPartitionRegistry.class), null,
                mock(io.github.cuihairu.redis.streaming.mq.broker.Broker.class));
        assertNotNull(c);
        closeQuietly(c);
    }

    @Test
    void permitsAreNoopsWhenBackpressureDisabled() throws Exception {
        RedisMessageConsumer plain = new RedisMessageConsumer(mock(org.redisson.api.RedissonClient.class), "unit-c2",
                mock(TopicPartitionRegistry.class), MqOptions.builder().maxInFlight(0).build());
        Class<?>[] none = {};
        assertDoesNotThrow(() -> invokePrivate(plain, "acquireInFlightPermit", none));
        assertDoesNotThrow(() -> invokePrivate(plain, "releaseInFlightPermit", none));
        closeQuietly(plain);
    }
}
