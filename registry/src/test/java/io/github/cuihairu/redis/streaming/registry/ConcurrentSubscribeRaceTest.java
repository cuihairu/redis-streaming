package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.event.ServiceChangeEvent;
import io.github.cuihairu.redis.streaming.registry.impl.RedisServiceConsumer;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for B-25: the containsKey check-then-act in subscribe let two
 * concurrent subscribers each register an RTopic listener for the same service —
 * every change event fired the handler twice, and the last unsubscribe only removed
 * one of the Redis subscriptions, leaking the other (and its handler) forever.
 */
class ConcurrentSubscribeRaceTest {

    @SuppressWarnings("unchecked")
    @Test
    void concurrentSubscribesRegisterExactlyOneTopicListener() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(redisson.<ServiceChangeEvent>getTopic(anyString(), any())).thenReturn(topic);

        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson);
        // start() only flips the running flag when health checking is disabled (default)
        consumer.start();

        int threads = 12;
        List<ServiceChangeListener> listeners = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            listeners.add((serviceName, action, instance, all) -> { });
        }

        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (ServiceChangeListener l : listeners) {
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    consumer.subscribe("svc-race", l);
                    return null;
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        // exactly one RTopic listener despite 12 racing subscribers (old code: up to 12)
        verify(topic, times(1)).addListener(any(Class.class), any());

        Field f = RedisServiceConsumer.class.getDeclaredField("subscriptions");
        f.setAccessible(true);
        Map<String, RTopic> subscriptions = (Map<String, RTopic>) f.get(consumer);
        assertEquals(1, subscriptions.size());

        // draining all listeners tears the subscription down exactly once
        for (ServiceChangeListener l : listeners) {
            consumer.unsubscribe("svc-race", l);
        }
        verify(topic, times(1)).removeAllListeners();
        assertEquals(0, subscriptions.size());
    }
}
