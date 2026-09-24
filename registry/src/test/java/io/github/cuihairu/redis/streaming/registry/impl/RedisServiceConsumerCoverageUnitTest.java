package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.Protocol;
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
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.api.listener.MessageListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Branch-complete unit coverage for RedisServiceConsumer internals (mock Redisson):
 * discovery variants, subscriptions, change events, health-status notifications and
 * the private parseProtocol/reportHealthStatus entry points.
 */
class RedisServiceConsumerCoverageUnitTest {

    private RedissonClient redisson;
    private RScript script;
    private RScoredSortedSet<String> heartbeats;
    private RTopic topic;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        heartbeats = mock(RScoredSortedSet.class);
        topic = mock(RTopic.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha-" + Math.abs(inv.getArgument(0).hashCode()));
        when(redisson.<String>getScoredSortedSet(anyString(), any(StringCodec.class))).thenReturn(heartbeats);
        when(redisson.getTopic(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(topic);
        when(heartbeats.getScore(anyString())).thenReturn((double) System.currentTimeMillis());
    }

    private ServiceConsumerConfig config() {
        ServiceConsumerConfig cfg = new ServiceConsumerConfig();
        cfg.setEnableHealthCheck(false);
        cfg.setHeartbeatTimeoutSeconds(60);
        return cfg;
    }

    private static Map<String, String> instanceData() {
        Map<String, String> data = new HashMap<>();
        data.put("host", "127.0.0.1");
        data.put("port", "1");
        data.put("protocol", "http");
        data.put("enabled", "true");
        data.put("healthy", "true");
        data.put("weight", "3");
        data.put("metadata", "{\"region\":\"cn\"}");
        return data;
    }

    @SuppressWarnings("unchecked")
    private void stubInstance(String serviceName, String instanceId, Map<String, String> data) {
        RMap<String, String> map = mock(RMap.class);
        when(map.readAllMap()).thenReturn(data);
        when(redisson.<String, String>getMap(
                eq(new ServiceConsumerConfig().getServiceInstanceKey(serviceName, instanceId)),
                any(StringCodec.class))).thenReturn(map);
    }

    @Test
    @SuppressWarnings("unchecked")
    void discoverCoversAllBranches() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();

        // happy path with cached discovery
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i1", "i2"));
        stubInstance("svc", "i1", instanceData());
        // i2 returns empty map -> skipped
        RMap<String, String> emptyMap = mock(RMap.class);
        when(emptyMap.readAllMap()).thenReturn(Map.of());
        when(redisson.<String, String>getMap(
                eq(new ServiceConsumerConfig().getServiceInstanceKey("svc", "i2")),
                any(StringCodec.class))).thenReturn(emptyMap);

        List<ServiceInstance> found = consumer.discover("svc");
        assertEquals(1, found.size());
        assertEquals("i1", found.get(0).getInstanceId());
        assertEquals(1, consumer.getDiscoveredInstanceCount());
        assertFalse(consumer.discoverHealthy("svc").isEmpty());

        // per-instance load failure -> warn and continue
        RMap<String, String> badMap = mock(RMap.class);
        when(badMap.readAllMap()).thenThrow(new IllegalStateException("hash corrupt"));
        when(redisson.<String, String>getMap(
                eq(new ServiceConsumerConfig().getServiceInstanceKey("svc", "i3")),
                any(StringCodec.class))).thenReturn(badMap);
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i3"));
        assertTrue(consumer.discover("svc").isEmpty());

        // invalid instance data (no host) -> buildServiceInstance null -> skipped
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i4"));
        stubInstance("svc", "i4", Map.of("port", "1"));
        assertTrue(consumer.discover("svc").isEmpty());

        // unhealthy/disabled instances are rejected by the healthy predicates
        Map<String, String> unhealthy = new HashMap<>(instanceData());
        unhealthy.put("healthy", "false");
        stubInstance("svc", "i5", unhealthy);
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i1", "i5"));
        assertFalse(consumer.isInstanceHealthy("i5"));
        assertEquals(1, consumer.discoverHealthy("svc").size());

        // outer failure -> empty list
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenThrow(new IllegalStateException("redis gone"));
        assertTrue(consumer.discover("svc").isEmpty());

        consumer.stop();
    }

    @Test
    void discoverThrowsWhenNotRunning() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        assertThrows(IllegalStateException.class, () -> consumer.discover("svc"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void isInstanceHeartbeatValidCoversMissingStaleFreshAndFailure() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i1"));
        stubInstance("svc", "i1", instanceData());

        // missing score -> invalid
        when(heartbeats.getScore("i1")).thenReturn(null);
        assertTrue(consumer.discoverHealthy("svc").isEmpty());

        // stale score -> invalid
        when(heartbeats.getScore("i1")).thenReturn((double) (System.currentTimeMillis() - 120_000L));
        assertTrue(consumer.discoverHealthy("svc").isEmpty());

        // fresh score -> valid
        when(heartbeats.getScore("i1")).thenReturn((double) System.currentTimeMillis());
        assertFalse(consumer.discoverHealthy("svc").isEmpty());

        // score lookup failure -> invalid
        when(heartbeats.getScore("i1")).thenThrow(new IllegalStateException("zset gone"));
        assertTrue(consumer.discoverHealthy("svc").isEmpty());

        consumer.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listServicesAndIsInstanceHealthy() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();

        RSet<String> services = mock(RSet.class);
        when(services.readAll()).thenReturn(Set.of("a", "b"));
        when(redisson.<String>getSet(anyString(), any(StringCodec.class))).thenReturn(services);
        assertEquals(2, consumer.listServices().size());

        when(redisson.<String>getSet(anyString(), any(StringCodec.class))).thenThrow(new IllegalStateException("down"));
        assertTrue(consumer.listServices().isEmpty());

        // no health check manager -> falls back to the discovered cache
        assertFalse(consumer.isInstanceHealthy("absent"));

        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i1"));
        stubInstance("svc", "i1", instanceData());
        consumer.discover("svc");
        assertTrue(consumer.isInstanceHealthy("i1"));

        consumer.stop();
        assertThrows(IllegalStateException.class, consumer::listServices);
    }

    @Test
    @SuppressWarnings("unchecked")
    void isInstanceHealthyDelegatesToHealthCheckManager() throws Exception {
        ServiceConsumerConfig cfg = new ServiceConsumerConfig();
        cfg.setEnableHealthCheck(true);
        cfg.setHealthCheckInterval(3600);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, cfg);
        consumer.start();
        assertNotNull(consumer.getHealthCheckManager());
        assertTrue(consumer.getActiveHealthCheckersCount() >= 0);
        // unknown instance -> manager returns false
        assertFalse(consumer.isInstanceHealthy("missing"));
        consumer.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void discoverByMetadataAndFiltersCoverLoadAndErrorBranches() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();

        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any(), any())).thenReturn(List.of("i1", "i2", "i3"));
        stubInstance("svc", "i1", instanceData());
        RMap<String, String> bad = mock(RMap.class);
        when(bad.readAllMap()).thenThrow(new IllegalStateException("corrupt"));
        when(redisson.<String, String>getMap(
                eq(new ServiceConsumerConfig().getServiceInstanceKey("svc", "i2")),
                any(StringCodec.class))).thenReturn(bad);

        // empty filters short-circuit to discover()
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean())).thenReturn(List.of());
        assertTrue(consumer.discoverByMetadata("svc", Map.of()).isEmpty());
        assertTrue(consumer.discoverByMetadata("svc", null).isEmpty());

        // metadata filters with one bad entry
        List<ServiceInstance> byMeta = consumer.discoverByMetadata("svc", Map.of("region", "cn"));
        assertEquals(1, byMeta.size());

        // filters combination (metadata + metrics)
        List<ServiceInstance> byFilters = consumer.discoverByFilters("svc", Map.of("a", "1"), Map.of("cpu", "1"));
        assertEquals(1, byFilters.size());
        assertFalse(consumer.discoverHealthyByFilters("svc", Map.of("a", "1"), Map.of("cpu", "1")).isEmpty());
        assertFalse(consumer.discoverHealthyByMetadata("svc", Map.of("region", "cn")).isEmpty());

        // unhealthy instance matched by filters is rejected by discoverHealthyByFilters
        Map<String, String> unhealthyData = new HashMap<>(instanceData());
        unhealthyData.put("healthy", "false");
        stubInstance("svc", "i7", unhealthyData);
        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any(), any())).thenReturn(List.of("i7"));
        assertEquals(1, consumer.discoverByFilters("svc", Map.of("a", "1"), Map.of("cpu", "1")).size());
        assertTrue(consumer.discoverHealthyByFilters("svc", Map.of("a", "1"), Map.of("cpu", "1")).isEmpty());

        // lua failure -> empty list
        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("lua broken"));
        assertTrue(consumer.discoverByMetadata("svc", Map.of("region", "cn")).isEmpty());
        assertTrue(consumer.discoverByFilters("svc", Map.of("a", "1"), null).isEmpty());

        consumer.stop();

        RedisServiceConsumer idle = new RedisServiceConsumer(redisson, config());
        assertThrows(IllegalStateException.class, () -> idle.discoverByFilters("x", null, null));
        assertThrows(IllegalStateException.class, () -> idle.discoverByMetadata("x", Map.of("a", "1")));

        // health-check registration branch of the filter discovery loops
        ServiceConsumerConfig healthCfg = new ServiceConsumerConfig();
        healthCfg.setEnableHealthCheck(true);
        healthCfg.setHealthCheckInterval(3600);
        RedisServiceConsumer healthConsumer = new RedisServiceConsumer(redisson, healthCfg);
        healthConsumer.start();
        when(script.evalSha(eq(RScript.Mode.READ_ONLY), anyString(), eq(RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any(), any())).thenReturn(List.of("i1", "i5", "i6"));
        stubInstance("svc", "i5", Map.of());
        stubInstance("svc", "i6", Map.of("port", "1"));
        assertEquals(1, healthConsumer.discoverByMetadata("svc", Map.of("region", "cn")).size());
        assertEquals(1, healthConsumer.discoverByFilters("svc", Map.of("region", "cn"), null).size());
        healthConsumer.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void subscribeUnsubscribeAndChangeEventsCoverAllBranches() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();

        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i1"));
        stubInstance("svc", "i1", instanceData());

        List<ServiceChangeEvent> events = new CopyOnWriteArrayList<>();
        ServiceChangeListener listener = (serviceName, action, instance, instances) ->
                events.add(new ServiceChangeEvent(serviceName, action,
                        instance != null ? instance.getInstanceId() : null, 0L, null));
        consumer.subscribe("svc", listener);

        // current-state notification on subscribe
        assertFalse(events.isEmpty());

        // capture the pub/sub listener and drive handleServiceChangeEvent
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<MessageListener> captor = ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(ServiceChangeEvent.class), captor.capture());
        @SuppressWarnings("unchecked")
        MessageListener<ServiceChangeEvent> pubsub = (MessageListener<ServiceChangeEvent>) captor.getValue();

        events.clear();
        // UPDATED event matching a live instance
        pubsub.onMessage("ch", new ServiceChangeEvent("svc", ServiceChangeAction.UPDATED, "i1", 1L, null));
        assertFalse(events.isEmpty());

        // REMOVED event with full snapshot incl. metadata
        events.clear();
        ServiceChangeEvent.InstanceSnapshot snap = new ServiceChangeEvent.InstanceSnapshot();
        snap.setHost("10.0.0.9");
        snap.setPort(9090);
        snap.setProtocol("tcp");
        snap.setEnabled(true);
        snap.setHealthy(false);
        snap.setWeight(7);
        snap.setMetadata(Map.of("zone", "z1"));
        pubsub.onMessage("ch", new ServiceChangeEvent("svc", ServiceChangeAction.REMOVED, "i9", 1L, snap));
        assertEquals(1, events.size());
        assertNotNull(events.get(0));

        // REMOVED event with snapshot but null metadata (skip the setMetadata inject)
        events.clear();
        ServiceChangeEvent.InstanceSnapshot snap2 = new ServiceChangeEvent.InstanceSnapshot();
        snap2.setHost("10.0.0.8");
        snap2.setPort(1);
        snap2.setProtocol("http");
        pubsub.onMessage("ch", new ServiceChangeEvent("svc", ServiceChangeAction.REMOVED, "i8", 1L, snap2));
        assertEquals(1, events.size());

        // REMOVED event without snapshot
        events.clear();
        pubsub.onMessage("ch", new ServiceChangeEvent("svc", ServiceChangeAction.REMOVED, "i7", 1L, null));
        assertEquals(1, events.size());

        // event whose listener throws is isolated
        consumer.subscribe("svc", (serviceName, action, instance, instances) -> {
            throw new IllegalStateException("listener failure");
        });
        events.clear();
        pubsub.onMessage("ch", new ServiceChangeEvent("svc", ServiceChangeAction.ADDED, "i1", 1L, null));
        assertFalse(events.isEmpty());

        // malformed event -> outer catch
        pubsub.onMessage("ch", null);

        // not-running guard (listeners still present, running flipped off)
        Field running = RedisServiceConsumer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(consumer, false);
        events.clear();
        pubsub.onMessage("ch", new ServiceChangeEvent("svc", ServiceChangeAction.UPDATED, "i1", 1L, null));
        assertTrue(events.isEmpty());
        running.set(consumer, true);

        // no-listeners guard
        for (ServiceChangeListener l : List.of(listener)) {
            consumer.unsubscribe("svc", l);
        }
        events.clear();
        pubsub.onMessage("ch", new ServiceChangeEvent("svc", ServiceChangeAction.UPDATED, "i1", 1L, null));
        assertTrue(events.isEmpty());

        consumer.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unsubscribeRemovesHealthCheckersForTargetServiceOnly() throws Exception {
        ServiceConsumerConfig cfg = new ServiceConsumerConfig();
        cfg.setEnableHealthCheck(true);
        cfg.setHealthCheckInterval(3600);
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, cfg);
        consumer.start();

        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i1"));
        stubInstance("svc", "i1", instanceData());
        stubInstance("other", "o1", instanceData());
        consumer.discover("svc");

        // seed a foreign service entry directly to cover the predicate false branch
        Field field = RedisServiceConsumer.class.getDeclaredField("discoveredInstances");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ServiceInstance> cache = (Map<String, ServiceInstance>) field.get(consumer);
        cache.put("o1", InstanceEntryCodec.parseInstance("other", "o1", instanceData()));

        ServiceChangeListener l1 = (serviceName, action, instance, instances) -> { };
        ServiceChangeListener l2 = (serviceName, action, instance, instances) -> { };
        consumer.subscribe("svc", l1);
        consumer.subscribe("svc", l2);
        consumer.unsubscribe("svc", l1); // one listener remains -> no teardown
        consumer.unsubscribe("unknown-service", l1); // no listeners -> no-op
        consumer.unsubscribe("svc", l2); // last listener -> full teardown incl. health checkers

        assertTrue(cache.containsKey("o1"), "foreign service entries must survive");
        consumer.stop();
    }

    @Test
    void stopCleansUpSubscriptionsAndToleratesListenerFailures() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        ServiceChangeListener l = (serviceName, action, instance, instances) -> { };
        consumer.subscribe("svc", l);
        doThrow(new IllegalStateException("topic gone")).when(topic).removeAllListeners();
        consumer.stop();
        consumer.stop(); // already stopped -> early return
    }

    @Test
    void parseProtocolCoversNullValidAndUnknown() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        Method parseProtocol = RedisServiceConsumer.class.getDeclaredMethod("parseProtocol", String.class);
        parseProtocol.setAccessible(true);
        assertEquals(StandardProtocol.HTTP, parseProtocol.invoke(consumer, (Object) null));
        assertEquals(StandardProtocol.TCP, parseProtocol.invoke(consumer, "tcp"));
        assertEquals(StandardProtocol.HTTP, parseProtocol.invoke(consumer, "bogus-protocol"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportHealthStatusAndNotifyCoverAllBranches() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i1"));
        stubInstance("svc", "i1", instanceData());
        consumer.discover("svc");

        Method report = RedisServiceConsumer.class.getDeclaredMethod("reportHealthStatus", String.class, boolean.class);
        report.setAccessible(true);

        // unknown instance -> early return
        report.invoke(consumer, "absent", false);

        List<ServiceChangeAction> actions = new CopyOnWriteArrayList<>();
        ServiceChangeListener ok = (serviceName, action, instance, instances) -> actions.add(action);
        ServiceChangeListener bad = (serviceName, action, instance, instances) -> {
            throw new IllegalStateException("listener failure");
        };

        // failure and recovery notifications with listeners (one of them throws)
        consumer.subscribe("svc", ok);
        consumer.subscribe("svc", bad);
        report.invoke(consumer, "i1", false);
        report.invoke(consumer, "i1", true);
        assertTrue(actions.contains(ServiceChangeAction.HEALTH_FAILURE));
        assertTrue(actions.contains(ServiceChangeAction.HEALTH_RECOVERY));

        // non-DefaultServiceInstance cache entry skips the rebuild branch
        Field field = RedisServiceConsumer.class.getDeclaredField("discoveredInstances");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ServiceInstance> cache = (Map<String, ServiceInstance>) field.get(consumer);
        cache.put("custom", new ServiceInstance() {
            @Override
            public String getServiceName() {
                return "svc";
            }

            @Override
            public String getInstanceId() {
                return "custom";
            }

            @Override
            public String getHost() {
                return "127.0.0.1";
            }

            @Override
            public int getPort() {
                return 1;
            }

            @Override
            public Map<String, String> getMetadata() {
                return Map.of();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public boolean isHealthy() {
                return false;
            }
        });
        actions.clear();
        report.invoke(consumer, "custom", true);
        assertTrue(actions.contains(ServiceChangeAction.HEALTH_RECOVERY));

        consumer.stop();
    }

    @Test
    void refreshServiceInstancesOnlyRunsWhenStarted() {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        assertDoesNotThrow(() -> consumer.refreshServiceInstances("svc"));
        consumer.start();
        assertDoesNotThrow(() -> consumer.refreshServiceInstances("svc"));
        consumer.stop();
    }

    @Test
    void getServiceAliasesDelegate() throws Exception {
        RedisServiceConsumer consumer = new RedisServiceConsumer(redisson, config());
        consumer.start();
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(new ArrayList<>());
        assertNotNull(consumer.getAllInstances("svc"));
        assertNotNull(consumer.getHealthyInstances("svc"));
        assertNotNull(consumer.getInstances("svc", true));
        assertNotNull(consumer.getInstances("svc", false));
        assertNotNull(consumer.getInstancesByMetadata("svc", null));
        assertNotNull(consumer.getHealthyInstancesByMetadata("svc", null));
        assertTrue(consumer.isRunning());
        consumer.stop();
        assertFalse(consumer.isRunning());
    }
}
