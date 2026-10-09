package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.broker.Broker;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.control.ReassignableMessageConsumer;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the dynamic-scaling reassignment primitive: an unknown topic is
 * rejected, a live subscription is re-pinned (with clamping), and only the workers whose
 * partition fell out of the new assignment are stopped — same topic but a different
 * group, and other topics, are untouched. Reflection-based like the sprint coverage
 * tests because the subscription/worker registries are internal.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisMessageConsumerReassignmentUnitTest {

    private RedissonClient client;
    private TopicPartitionRegistry partitionRegistry;
    private RedisMessageConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(RedissonClient.class);
        RStream<String, Object> dataStream = mock(RStream.class);
        RLock lock = mock(RLock.class);
        when(client.getStream(anyString(), any(Codec.class))).thenReturn((RStream) dataStream);
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        partitionRegistry = mock(TopicPartitionRegistry.class);
        when(partitionRegistry.getPartitionCount(anyString())).thenReturn(1);

        consumer = new RedisMessageConsumer(client, "reassign", partitionRegistry,
                MqOptions.builder().build(), mock(Broker.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            field(consumer, "running").set(consumer, new AtomicBoolean(false));
            field(consumer, "closed").set(consumer, new AtomicBoolean(false));
            invoke(consumer, "close", new Class<?>[]{});
        } catch (Throwable ignore) {
        }
    }

    @Test
    void unknownTopicIsRejected() {
        ReassignableMessageConsumer rmc = consumer;
        assertFalse(rmc.updatePartitionAssignment("ghost", 2, 0));
    }

    @Test
    void interfaceDefaultIsFalse() {
        ReassignableMessageConsumer noop = new ReassignableMessageConsumer() {
        };
        assertFalse(noop.updatePartitionAssignment("t", 2, 0));
    }

    @Test
    void repinClampsAndUpdatesTheSubscription() throws Exception {
        subscribe("t", "g");
        ReassignableMessageConsumer rmc = consumer;

        assertTrue(rmc.updatePartitionAssignment("t", 0, -3));
        Object sub = subscription("t");
        assertEquals(1, subInt(sub, "partitionModulo"));
        assertEquals(0, subInt(sub, "partitionRemainder"));

        assertTrue(rmc.updatePartitionAssignment("t", 4, 2));
        assertEquals(4, subInt(sub, "partitionModulo"));
        assertEquals(2, subInt(sub, "partitionRemainder"));
    }

    @Test
    void repinStopsOnlyWorkersOutOfTheNewAssignment() throws Exception {
        subscribe("t", "g");
        Object p0 = worker("t", "g", 0);
        Object p1 = worker("t", "g", 1);
        Object p2 = worker("t", "g", 2);
        Object p3 = worker("t", "g", 3);

        assertTrue(consumer.updatePartitionAssignment("t", 2, 1));

        // 1 % 2 == 1 stays; 0 and 2 fall out and are stopped for prompt lease handover
        assertFalse(running(p0));
        assertTrue(running(p1));
        assertFalse(running(p2));
        assertTrue(running(p3));
    }

    @Test
    void otherTopicAndOtherGroupWorkersAreUntouched() throws Exception {
        subscribe("t1", "g1");
        Object mine = worker("t1", "g1", 0);
        Object sameTopicInAssignment = worker("t1", "g1", 3);
        Object otherGroup = worker("t1", "g9", 0);
        Object otherTopic = worker("t2", "g1", 0);

        assertTrue(consumer.updatePartitionAssignment("t1", 2, 1));

        assertFalse(running(mine));
        assertTrue(running(sameTopicInAssignment));
        assertTrue(running(otherGroup));
        assertTrue(running(otherTopic));
    }

    @Test
    void repinningAgainStopsNewlyOutOfAssignmentWorkers() throws Exception {
        subscribe("t", "g");
        Object p0 = worker("t", "g", 0);
        Object p1 = worker("t", "g", 1);

        assertTrue(consumer.updatePartitionAssignment("t", 2, 1));
        assertTrue(running(p1));
        assertFalse(running(p0));

        assertTrue(consumer.updatePartitionAssignment("t", 2, 0));
        assertFalse(running(p1));
        // p0 was already stopped by the first repin and stays stopped
        assertFalse(running(p0));
    }

    // ===== reflection helpers =====

    private void subscribe(String topic, String group) throws Exception {
        Map<String, Object> subs = (Map<String, Object>) field(consumer, "subscriptions").get(consumer);
        subs.put(topic, newSubscription(topic, group, m -> MessageHandleResult.SUCCESS));
    }

    private Object subscription(String topic) throws Exception {
        Map<String, Object> subs = (Map<String, Object>) field(consumer, "subscriptions").get(consumer);
        return subs.get(topic);
    }

    private Object worker(String topic, String group, int pid) throws Exception {
        Object w = newPartitionWorker(topic, group, pid, m -> MessageHandleResult.SUCCESS);
        Map<Object, Object> workers = (Map<Object, Object>) field(consumer, "workers").get(consumer);
        workers.put(newPartitionKey(topic, group, pid), w);
        return w;
    }

    private static Field field(Object target, String name) throws Exception {
        Field f = RedisMessageConsumer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = RedisMessageConsumer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Object newSubscription(String topic, String group,
                                          io.github.cuihairu.redis.streaming.mq.MessageHandler handler)
            throws Exception {
        Class<?> cls = Class.forName(RedisMessageConsumer.class.getName() + "$Subscription");
        Constructor<?> c = cls.getDeclaredConstructor(String.class, String.class,
                io.github.cuihairu.redis.streaming.mq.MessageHandler.class);
        c.setAccessible(true);
        return c.newInstance(topic, group, handler);
    }

    private static Object newPartitionWorker(String topic, String group, int pid,
                                             io.github.cuihairu.redis.streaming.mq.MessageHandler handler)
            throws Exception {
        Class<?> cls = Class.forName(RedisMessageConsumer.class.getName() + "$PartitionWorker");
        Constructor<?> c = cls.getDeclaredConstructor(String.class, String.class, int.class,
                io.github.cuihairu.redis.streaming.mq.MessageHandler.class);
        c.setAccessible(true);
        return c.newInstance(topic, group, pid, handler);
    }

    private static Object newPartitionKey(String topic, String group, int pid) throws Exception {
        Class<?> cls = Class.forName(RedisMessageConsumer.class.getName() + "$PartitionKey");
        Constructor<?> c = cls.getDeclaredConstructor(String.class, String.class, int.class);
        c.setAccessible(true);
        return c.newInstance(topic, group, pid);
    }

    private static int subInt(Object sub, String name) throws Exception {
        Field f = sub.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return ((Number) f.get(sub)).intValue();
    }

    private static boolean running(Object worker) throws Exception {
        Field f = worker.getClass().getDeclaredField("running");
        f.setAccessible(true);
        return ((AtomicBoolean) f.get(worker)).get();
    }
}
