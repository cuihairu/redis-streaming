package io.github.cuihairu.redis.streaming.config;

import io.github.cuihairu.redis.streaming.config.event.ConfigChangeEvent;
import io.github.cuihairu.redis.streaming.config.impl.RedisConfigService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Field;
import java.util.Collections;
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
 * Regression test for B-25 (config side): concurrent addListener calls for the same
 * group:dataId used to each register an RTopic listener via containsKey check-then-act —
 * every change event fired handlers twice and the last removeListener left one Redis
 * subscription leaking forever.
 */
class ConfigServiceConcurrentAddListenerRaceTest {

    @SuppressWarnings("unchecked")
    @Test
    void concurrentAddListenersRegisterExactlyOneTopicListener() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RTopic topic = mock(RTopic.class);
        when(redisson.<ConfigChangeEvent>getTopic(anyString(), any())).thenReturn(topic);

        @SuppressWarnings("unchecked")
        RSet<String> subscribers = mock(RSet.class);
        when(redisson.<String>getSet(anyString())).thenReturn(subscribers);

        RMap<String, String> configMap = mock(RMap.class);
        when(redisson.getMap(anyString(), any(org.redisson.client.codec.StringCodec.class)))
                .thenReturn((RMap) configMap);
        when(configMap.readAllMap()).thenReturn(Collections.emptyMap());

        RedisConfigService service = new RedisConfigService(redisson, new ConfigServiceConfig());
        service.start();

        int threads = 12;
        List<ConfigChangeListener> listeners = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            listeners.add((dataId, group, content, version) -> { });
        }

        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (ConfigChangeListener l : listeners) {
                pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    service.addListener("data", "group", l);
                    return null;
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        // exactly one RTopic listener despite 12 racing addListener calls (old code: up to 12)
        verify(topic, times(1)).addListener(any(Class.class), any());

        Field f = RedisConfigService.class.getDeclaredField("subscriptions");
        f.setAccessible(true);
        Map<String, RTopic> subscriptions = (Map<String, RTopic>) f.get(service);
        assertEquals(1, subscriptions.size());

        for (ConfigChangeListener l : listeners) {
            service.removeListener("data", "group", l);
        }
        verify(topic, times(1)).removeAllListeners();
        assertEquals(0, subscriptions.size());
    }
}
