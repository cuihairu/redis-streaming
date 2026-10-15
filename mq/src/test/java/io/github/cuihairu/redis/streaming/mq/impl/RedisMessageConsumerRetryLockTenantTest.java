package io.github.cuihairu.redis.streaming.mq.impl;

import io.github.cuihairu.redis.streaming.mq.MessageHandleResult;
import io.github.cuihairu.redis.streaming.mq.MessageHandler;
import io.github.cuihairu.redis.streaming.mq.config.MqOptions;
import io.github.cuihairu.redis.streaming.mq.partition.StreamKeys;
import io.github.cuihairu.redis.streaming.mq.partition.TopicPartitionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retry mover must take its per-topic lock from {@link StreamKeys} (control prefix +
 * tenant segment), not from a hard-coded global prefix: a shared lock would make two
 * tenants running the same topic name serialize on each other.
 */
class RedisMessageConsumerRetryLockTenantTest {

    private final List<RedisMessageConsumer> consumers = new ArrayList<>();
    private final List<String> lockKeys = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (RedisMessageConsumer consumer : consumers) {
            ((AtomicBoolean) field(consumer, "running")).set(false);
            try {
                invoke(consumer, "close");
            } catch (Throwable ignore) {
            }
        }
        consumers.clear();
        lockKeys.clear();
    }

    @Test
    void defaultTenantKeepsTheLegacyLockKey() throws Exception {
        RedissonClient client = client();
        RedisMessageConsumer consumer = consumer(client, MqOptions.builder().build());

        runRetryMover(client, consumer);

        assertEquals(List.of("streaming:mq:retry:lock:orders"), lockKeys);
    }

    @Test
    void nonDefaultTenantTakesItsOwnLockKey() throws Exception {
        RedissonClient client = client();
        RedisMessageConsumer consumer = consumer(client, MqOptions.builder().tenant("acme").build());

        runRetryMover(client, consumer);

        assertEquals(List.of("streaming:mq:acme:retry:lock:orders"), lockKeys);
    }

    @Test
    void twoTenantsOnTheSameTopicNeverShareTheLock() throws Exception {
        RedissonClient acmeClient = client();
        RedissonClient betaClient = client();
        runRetryMover(acmeClient, consumer(acmeClient, MqOptions.builder().tenant("acme").build()));
        String acmeLock = lockKeys.get(lockKeys.size() - 1);
        runRetryMover(betaClient, consumer(betaClient, MqOptions.builder().tenant("beta").build()));
        String betaLock = lockKeys.get(lockKeys.size() - 1);

        assertEquals("streaming:mq:acme:retry:lock:orders", acmeLock);
        assertEquals("streaming:mq:beta:retry:lock:orders", betaLock);
    }

    // ===== helpers =====

    /** A Redisson client that records every lock name it is asked for. */
    private RedissonClient client() throws InterruptedException {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock(anyString())).thenAnswer(invocation -> {
            lockKeys.add(invocation.getArgument(0));
            return lock;
        });
        when(client.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(mock(RScript.class));
        // keep the mover out of the Lua path: one lock acquisition per subscription is enough
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        return client;
    }

    private RedisMessageConsumer consumer(RedissonClient client, MqOptions options) throws Exception {
        TopicPartitionRegistry registry = mock(TopicPartitionRegistry.class);
        when(registry.getPartitionCount(anyString())).thenReturn(1);
        RedisMessageConsumer consumer =
                new RedisMessageConsumer(client, "retry-lock-tenant", registry, options);
        MessageHandler handler = msg -> MessageHandleResult.SUCCESS;
        ((Map) field(consumer, "subscriptions")).put("orders", subscription("orders", "g", handler));
        ((AtomicBoolean) field(consumer, "running")).set(true);
        consumers.add(consumer);
        return consumer;
    }

    /** Run one retry-mover pass; it must take exactly one lock, on the client's own view. */
    private void runRetryMover(RedissonClient client, RedisMessageConsumer consumer) throws Exception {
        int before = lockKeys.size();
        invoke(consumer, "moveDueRetries");
        assertEquals(before + 1, lockKeys.size(), "retry mover must take exactly one lock");
        verify(client).getLock(lockKeys.get(lockKeys.size() - 1));
    }

    private static Object subscription(String topic, String group, MessageHandler handler) throws Exception {
        Class<?> cls = Class.forName(RedisMessageConsumer.class.getName() + "$Subscription");
        Constructor<?> ctor = cls.getDeclaredConstructor(String.class, String.class, MessageHandler.class);
        ctor.setAccessible(true);
        return ctor.newInstance(topic, group, handler);
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = RedisMessageConsumer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method m = RedisMessageConsumer.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }
}