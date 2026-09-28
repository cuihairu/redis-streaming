package io.github.cuihairu.redis.streaming.config.impl;

import io.github.cuihairu.redis.streaming.config.ConfigChangeListener;
import io.github.cuihairu.redis.streaming.config.ConfigServiceConfig;
import io.github.cuihairu.redis.streaming.config.event.ConfigChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch residuals on the listener dispatch paths of {@link RedisConfigService}, driven
 * entirely through mocks (no Redis): the B-07 pub/sub loopback skip vs. foreign/markerless
 * delivery, the B-06 duplicate-delivery idempotency gate, the resync poll's structural
 * guards, and the poll wrapper's Throwable backstop.
 */
class RedisConfigServiceListenerDispatchBranchesTest {

    private RedissonClient redisson;
    private RMap<String, String> configMap;
    private RTopic topic;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        configMap = mock(RMap.class);
        topic = mock(RTopic.class);
        when(redisson.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn(configMap);
        when(redisson.<String>getSet(anyString())).thenAnswer(inv -> mock(RSet.class));
        when(redisson.getTopic(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(topic);
        when(redisson.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(mock(RScript.class));
    }

    private RedisConfigService startedService() {
        // resyncIntervalMs=0 disables the background poll: the tests below drive the
        // resync/dispatch paths synchronously and must not race the scheduler
        ConfigServiceConfig cfg = new ConfigServiceConfig("dispatch-test", true);
        cfg.setResyncIntervalMs(0);
        RedisConfigService service = new RedisConfigService(redisson, cfg);
        service.start();
        return service;
    }

    @SuppressWarnings("unchecked")
    private MessageListener<ConfigChangeEvent> capturedPubSubListener(RedisConfigService service) {
        ArgumentCaptor<MessageListener<ConfigChangeEvent>> captor =
                ArgumentCaptor.forClass((Class) MessageListener.class);
        verify(topic).addListener(eq(ConfigChangeEvent.class), captor.capture());
        return captor.getValue();
    }

    private static String clientIdOf(RedisConfigService service) throws Exception {
        Field f = RedisConfigService.class.getDeclaredField("clientId");
        f.setAccessible(true);
        return (String) f.get(service);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pubSubLoopbackFromOwnPublishIsSkippedForeignAndMarkerlessStillDispatch() throws Exception {
        RedisConfigService service = startedService();
        AtomicInteger calls = new AtomicInteger();
        when(configMap.readAllMap()).thenReturn(Map.of("content", "v1", "version", "v9"));
        service.addListener("d", "g", (id, g, c, v) -> calls.incrementAndGet());
        assertEquals(1, calls.get(), "the subscribe-time snapshot notifies once");

        MessageListener<ConfigChangeEvent> pubsub = capturedPubSubListener(service);

        // B-07: the event this JVM itself published loops back through pub/sub and must
        // be dropped, the synchronous local delivery already covered it. A content the
        // listeners never saw makes the assertion sharp: a missing skip would dispatch
        // (the B-06 gate only dedups identical states).
        pubsub.onMessage("ch", new ConfigChangeEvent("d", "g", "self", "vX", 1L, clientIdOf(service)));
        assertEquals(1, calls.get(), "own loopback must not dispatch a second time");

        // a foreign publisher's event dispatches through the subscription
        pubsub.onMessage("ch", new ConfigChangeEvent("d", "g", "v2", "v10", 1L, "other-client"));
        assertEquals(2, calls.get());

        // pre-B-07 publishers send no marker; the event must still dispatch
        pubsub.onMessage("ch", new ConfigChangeEvent("d", "g", "v3", "v11", 1L));
        assertEquals(3, calls.get());
        service.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateDeliveryOfAnAlreadySeenStateIsANoOp() {
        RedisConfigService service = startedService();
        AtomicInteger calls = new AtomicInteger();
        when(configMap.readAllMap()).thenReturn(Map.of("content", "v1", "version", "v9"));
        service.addListener("d", "g", (id, g, c, v) -> calls.incrementAndGet());
        assertEquals(1, calls.get());

        MessageListener<ConfigChangeEvent> pubsub = capturedPubSubListener(service);

        // same (content, version) the listeners were already told: the B-06 idempotency
        // gate must swallow the duplicate (poll vs. pub/sub race defense in depth)
        pubsub.onMessage("ch", new ConfigChangeEvent("d", "g", "v1", "v9", 2L, "other-client"));
        assertEquals(1, calls.get(), "identical state must not re-notify");

        // a genuinely new state still gets through
        pubsub.onMessage("ch", new ConfigChangeEvent("d", "g", "v2", "v10", 3L, "other-client"));
        assertEquals(2, calls.get());
        service.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resyncSkipsEmptyListenerSetsAndMalformedKeys() throws Exception {
        RedisConfigService service = startedService();

        Field listenersField = RedisConfigService.class.getDeclaredField("listeners");
        listenersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Set<ConfigChangeListener>> listeners =
                (Map<String, Set<ConfigChangeListener>>) listenersField.get(service);
        // a key whose listener set drained to empty (structural guard, no Redis read)
        listeners.put("g:empty", ConcurrentHashMap.newKeySet());
        // a key that never came from "group:dataId" concatenation (malformed guard)
        Set<ConfigChangeListener> orphan = ConcurrentHashMap.newKeySet();
        orphan.add((id, g, c, v) -> { });
        listeners.put("no-separator", orphan);

        assertDoesNotThrow(service::resyncSubscribedConfigs);
        verify(configMap, never()).readAllMap();
        service.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resyncSafelyBackstopsErrorsFromThePollBody() throws Exception {
        RedisConfigService service = startedService();

        Field listenersField = RedisConfigService.class.getDeclaredField("listeners");
        listenersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Set<ConfigChangeListener>> listeners =
                (Map<String, Set<ConfigChangeListener>>) listenersField.get(service);
        Set<ConfigChangeListener> live = ConcurrentHashMap.newKeySet();
        live.add((id, g, c, v) -> { });
        listeners.put("g:d", live);

        // an Error is not an Exception, so the per-entry catch lets it escape the poll
        // body; resyncSafely must swallow it so the scheduled task survives
        when(configMap.readAllMap()).thenThrow(new AssertionError("redis client gone"));

        java.lang.reflect.Method m = RedisConfigService.class.getDeclaredMethod("resyncSafely");
        m.setAccessible(true);
        assertDoesNotThrow(() -> m.invoke(service));
        verify(configMap).readAllMap();
        service.stop();
    }
}
