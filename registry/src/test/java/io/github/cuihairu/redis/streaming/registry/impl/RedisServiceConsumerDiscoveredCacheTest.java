package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceChangeAction;
import io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.event.ServiceChangeEvent;
import io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-45 regression: the discoveredInstances cache must be keyed per service (uniqueId
 * "serviceName:instanceId", not the bare instanceId two services can share) and must
 * not outlive its instances — a discovery cycle reconciles entries whose heartbeat
 * expired and a REMOVED change event evicts immediately. Uses only the public API,
 * so it reproduces on the pre-fix code.
 */
class RedisServiceConsumerDiscoveredCacheTest {

    private RedissonClient redisson;
    private RScript script;
    private RTopic topic;
    private final Map<String, RScoredSortedSet<String>> heartbeatsByKey = new ConcurrentHashMap<>();
    private final Map<String, RMap<String, String>> mapsByKey = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        topic = mock(RTopic.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha-" + Math.abs(inv.getArgument(0).hashCode()));
        when(redisson.<String>getScoredSortedSet(anyString(), any(StringCodec.class)))
                .thenAnswer(inv -> heartbeatsByKey.computeIfAbsent(inv.getArgument(0),
                        key -> mock(RScoredSortedSet.class)));
        when(redisson.<String, String>getMap(anyString(), any(StringCodec.class)))
                .thenAnswer(inv -> mapsByKey.computeIfAbsent(inv.getArgument(0),
                        key -> mock(RMap.class)));
        when(redisson.getTopic(anyString(), any(Codec.class))).thenReturn(topic);
    }

    private ServiceConsumerConfig config() {
        ServiceConsumerConfig cfg = new ServiceConsumerConfig();
        cfg.setEnableHealthCheck(false);
        cfg.setHeartbeatTimeoutSeconds(60);
        return cfg;
    }

    private void active(String serviceName, String... instanceIds) {
        RScoredSortedSet<String> heartbeats = heartbeatsByKey.computeIfAbsent(
                new ServiceConsumerConfig().getHeartbeatKey(serviceName), key -> mock(RScoredSortedSet.class));
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of(instanceIds));
    }

    private void stubInstance(String serviceName, String instanceId, boolean healthy) {
        Map<String, String> data = new HashMap<>();
        data.put("host", "127.0.0.1");
        data.put("port", "8080");
        data.put("protocol", "http");
        data.put("enabled", "true");
        data.put("healthy", String.valueOf(healthy));
        RMap<String, String> map = mapsByKey.computeIfAbsent(
                new ServiceConsumerConfig().getServiceInstanceKey(serviceName, instanceId),
                key -> mock(RMap.class));
        when(map.readAllMap()).thenReturn(data);
    }

    @Test
    void sameInstanceIdInDifferentServicesDoesNotCollapse() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        try {
            active("svc-a", "host-1");
            stubInstance("svc-a", "host-1", true);
            active("svc-b", "host-1");
            stubInstance("svc-b", "host-1", true);

            assertEquals(1, consumer.discover("svc-a").size());
            assertEquals(1, consumer.discover("svc-b").size());

            assertEquals(2, consumer.getDiscoveredInstanceCount(),
                    "two services sharing an instanceId (e.g. the hostname) are two instances");
        } finally {
            consumer.stop();
        }
    }

    @Test
    void discoveryReconcilesExpiredEntriesAway() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        try {
            active("svc", "i1", "i2");
            stubInstance("svc", "i1", true);
            stubInstance("svc", "i2", true);
            consumer.discover("svc");
            assertEquals(2, consumer.getDiscoveredInstanceCount());

            // i2's heartbeat window elapses: the next discovery must drop it from the
            // cache too, not only from the returned list
            active("svc", "i1");
            assertEquals(1, consumer.discover("svc").size());

            assertEquals(1, consumer.getDiscoveredInstanceCount(),
                    "an expired instance must not stay cached forever");
            assertFalse(consumer.isInstanceHealthy("i2"),
                    "an expired instance must stop answering health lookups");
        } finally {
            consumer.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void removedEventEvictsTheCacheEntry() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        try {
            active("svc", "i1");
            stubInstance("svc", "i1", true);
            consumer.discover("svc");
            assertEquals(1, consumer.getDiscoveredInstanceCount());

            consumer.subscribe("svc", (serviceName, action, instance, instances) -> { });

            ArgumentCaptor<org.redisson.api.listener.MessageListener> captor =
                    ArgumentCaptor.forClass(MessageListener.class);
            verify(topic).addListener(eq(ServiceChangeEvent.class), captor.capture());
            MessageListener<ServiceChangeEvent> pubsub =
                    (MessageListener<ServiceChangeEvent>) captor.getValue();

            pubsub.onMessage("ch", new ServiceChangeEvent("svc", ServiceChangeAction.REMOVED, "i1", 1L, null));

            assertEquals(0, consumer.getDiscoveredInstanceCount(),
                    "a REMOVED event must evict the cached instance immediately");
        } finally {
            consumer.stop();
        }
    }

    @Test
    void ambiguousInstanceIdIsHealthyOnlyWhenAllMatchesAreHealthy() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        try {
            active("svc-a", "host-1");
            stubInstance("svc-a", "host-1", false);
            active("svc-b", "host-1");
            stubInstance("svc-b", "host-1", true);
            consumer.discover("svc-a");
            consumer.discover("svc-b");

            assertFalse(consumer.isInstanceHealthy("host-1"),
                    "a bare id shared by an unhealthy and a healthy instance must not "
                            + "resolve to whichever service was discovered last");
        } finally {
            consumer.stop();
        }
    }

    @Test
    void discoverByMetadataAlsoKeysByService() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        try {
            when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                    any(), any(), any(), any(), any())).thenReturn(List.of("host-1"));
            stubInstance("svc-a", "host-1", true);
            stubInstance("svc-b", "host-1", true);

            assertEquals(1, consumer.discoverByMetadata("svc-a", Map.of("region", "cn")).size());
            assertEquals(1, consumer.discoverByMetadata("svc-b", Map.of("region", "cn")).size());

            assertEquals(2, consumer.getDiscoveredInstanceCount(),
                    "the metadata-filtered discovery path must key the cache per service too");
        } finally {
            consumer.stop();
        }
    }

    @Test
    void cacheExposureStaysConsistentWithTheServiceInstanceContract() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        try {
            active("svc", "i1");
            stubInstance("svc", "i1", true);
            List<ServiceInstance> found = consumer.discover("svc");

            assertEquals(1, consumer.getDiscoveredInstanceCount());
            assertEquals(DefaultServiceInstance.class, found.get(0).getClass());
            assertEquals("svc:i1", found.get(0).getUniqueId(),
                    "the cache key space is the uniqueId space");
            assertEquals(StandardProtocol.HTTP, found.get(0).getProtocol());
        } finally {
            consumer.stop();
        }
    }
}
