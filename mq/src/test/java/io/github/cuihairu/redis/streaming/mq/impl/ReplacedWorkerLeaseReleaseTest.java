package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.lease.LeaseManager;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MQ-15: renewLeases() removing a lease-renewal-failed worker and rebalanceAssignments()
 * starting a successor for the same partition can interleave (two scheduler threads): the
 * retiring worker is still draining its blocking read when the successor registers. Both
 * workers carry the SAME owner name, so the retire-time releaseIfOwner compare-and-delete
 * silently deletes the successor's live lease (double releaseIfOwner / lease theft).
 *
 * The scenario is driven deterministically through the maintenance methods while the old
 * worker is parked inside a gated read — no timing reliance for the interleaving itself.
 */
@SuppressWarnings({"unchecked", "deprecation"})
class ReplacedWorkerLeaseReleaseTest {

    private static final String NAME = "mq15-c";
    private static final Class<?>[] NO_ARGS = {};

    private RedisMessageConsumer consumer;
    private LeaseManager lease;
    private Map<Object, Object> workers;

    private final AtomicInteger reads = new AtomicInteger();
    private final CountDownLatch firstReadGate = new CountDownLatch(1);
    private final CountDownLatch laterReadGate = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        RedissonClient client = mock(RedissonClient.class);
        Broker broker = mock(Broker.class);
        TopicPartitionRegistry registry = mock(TopicPartitionRegistry.class);
        when(registry.getPartitionCount(anyString())).thenReturn(1);
        MqOptions options = MqOptions.builder()
                .workerThreads(4).schedulerThreads(4)
                .rebalanceIntervalSec(3600).renewIntervalSec(3600)
                .pendingScanIntervalSec(3600).retryMoverIntervalSec(3600)
                .leaseTtlSeconds(60)
                .build();
        consumer = new RedisMessageConsumer(client, NAME, registry, options, broker);
        lease = mock(LeaseManager.class);
        setField(consumer, "leaseManager", lease);
        installSubscription("t", "g", m -> MessageHandleResult.SUCCESS);
        workers = (Map<Object, Object>) getField(consumer, "workers");
        runningField().set(true);

        when(broker.readGroup(anyString(), anyString(), anyString(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    int n = reads.incrementAndGet();
                    if (n == 1) {
                        firstReadGate.await(30, TimeUnit.SECONDS); // old worker parks here
                    } else {
                        laterReadGate.await(30, TimeUnit.SECONDS); // successor parks here
                    }
                    return List.of();
                });
    }

    @AfterEach
    void tearDown() {
        firstReadGate.countDown();
        laterReadGate.countDown();
        try {
            consumer.close();
        } catch (Exception ignore) {
        }
    }

    @Test
    void drainingRetiredWorkerMustNotReleaseSuccessorsLease() throws Exception {
        // rebalance #1: tryAcquire succeeds -> worker w1 registered, parked in readGroup#1
        when(lease.tryAcquire(anyString(), eq(NAME), anyLong())).thenReturn(true).thenReturn(false);
        when(lease.isOwner(anyString(), eq(NAME))).thenReturn(true);
        when(lease.renewIfOwner(anyString(), eq(NAME), anyLong())).thenReturn(false); // false-positive

        invoke("rebalanceAssignments");
        Object pk = newPartitionKey("t", "g", 0);
        Object w1 = workers.get(pk);
        assertNotNull(w1, "first rebalance must register a worker");

        // Barrier: the retirement scenario is only observable while w1 is parked INSIDE its
        // first read. Under load the pool thread can start late, letting w1 exit straight into
        // its finally (slot already empty -> legitimate release) before renew even runs.
        long parked = System.currentTimeMillis() + 10_000;
        while (reads.get() < 1 && System.currentTimeMillis() < parked) {
            Thread.sleep(20);
        }
        assertTrue(reads.get() >= 1, "old worker must be parked inside its first read before renewal");

        // renewal false-positive: w1 is stopped and unregistered while still draining
        invoke("renewLeases");
        assertFalse(workerRunning(w1), "renew must stop the lease-lost worker");
        assertNull(workers.get(pk), "renew must unregister the lease-lost worker");

        // rebalance runs concurrently: the lease key still names us, so a successor starts
        invoke("rebalanceAssignments");
        Object w2 = workers.get(pk);
        assertNotNull(w2, "successor must be registered from the isOwner path");
        assertNotSame(w1, w2);

        firstReadGate.countDown(); // w1 returns from its read and exits its loop

        // MQ-15: the retired worker must NOT compare-and-delete the successor's lease —
        // both carry the same owner name, so the release would silently orphan the partition.
        verify(lease, after(2_000).never()).releaseIfOwner(eq(StreamKeys.lease("t", "g", 0)), eq(NAME));
        assertSame(w2, workers.get(pk), "successor registration must survive the retirement");
    }

    @Test
    void stoppedConsumerStillReleasesLeaseOnWorkerExit() throws Exception {
        // control: stop() unregisters before the worker drains — release must still happen
        when(lease.tryAcquire(anyString(), eq(NAME), anyLong())).thenReturn(true);
        invoke("rebalanceAssignments");
        assertNotNull(workers.get(newPartitionKey("t", "g", 0)));

        consumer.stop();
        firstReadGate.countDown();

        verify(lease, timeout(2_000)).releaseIfOwner(eq(StreamKeys.lease("t", "g", 0)), eq(NAME));
    }

    @Test
    void unsubscribeStillReleasesLeaseOfDrainingWorker() throws Exception {
        // control: unsubscribe removes+stops the worker — the draining exit must release
        when(lease.tryAcquire(anyString(), eq(NAME), anyLong())).thenReturn(true);
        invoke("rebalanceAssignments");
        assertNotNull(workers.get(newPartitionKey("t", "g", 0)));

        consumer.unsubscribe("t");
        firstReadGate.countDown();

        verify(lease, timeout(2_000)).releaseIfOwner(eq(StreamKeys.lease("t", "g", 0)), eq(NAME));
    }

    // ===== reflection helpers (signatures are identical pre/post fix) =====

    private AtomicBoolean runningField() throws Exception {
        return (AtomicBoolean) getField(consumer, "running");
    }

    private void invoke(String method) throws Exception {
        Method m = RedisMessageConsumer.class.getDeclaredMethod(method, NO_ARGS);
        m.setAccessible(true);
        try {
            m.invoke(consumer);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static void installSubscription(RedisMessageConsumer target, String topic, String group,
                                            io.github.cuihairu.redis.streaming.mq.MessageHandler handler) throws Exception {
        Class<?> cls = Class.forName(RedisMessageConsumer.class.getName() + "$Subscription");
        Constructor<?> c = cls.getDeclaredConstructor(String.class, String.class,
                io.github.cuihairu.redis.streaming.mq.MessageHandler.class);
        c.setAccessible(true);
        Map<String, Object> subs = (Map<String, Object>) field(target, "subscriptions").get(target);
        subs.put(topic, c.newInstance(topic, group, handler));
    }

    private void installSubscription(String topic, String group,
                                     io.github.cuihairu.redis.streaming.mq.MessageHandler handler) throws Exception {
        installSubscription(consumer, topic, group, handler);
    }

    private static boolean workerRunning(Object worker) throws Exception {
        Field f = Class.forName(RedisMessageConsumer.class.getName() + "$PartitionWorker")
                .getDeclaredField("running");
        f.setAccessible(true);
        return ((AtomicBoolean) f.get(worker)).get();
    }

    private static Object newPartitionKey(String topic, String group, int pid) throws Exception {
        Class<?> cls = Class.forName(RedisMessageConsumer.class.getName() + "$PartitionKey");
        Constructor<?> c = cls.getDeclaredConstructor(String.class, String.class, int.class);
        c.setAccessible(true);
        return c.newInstance(topic, group, pid);
    }

    private static Field field(Object target, String name) throws Exception {
        Field f = RedisMessageConsumer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object getField(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        field(target, name).set(target, value);
    }
}
