package io.github.cuihairu.redis.streaming.registry.impl;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceProviderConfig;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.LoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for RedisNamingService delegation paths: subscribe/unsubscribe,
 * discoverByMetadata and the load-balancer convenience choosers.
 */
class RedisNamingServiceCoverageUnitTest {

    private RedissonClient redisson;
    private RScript script;
    private RScoredSortedSet<String> heartbeats;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisson = mock(RedissonClient.class);
        script = mock(RScript.class);
        heartbeats = mock(RScoredSortedSet.class);
        when(redisson.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.scriptLoad(anyString())).thenAnswer(inv -> "sha-" + Math.abs(inv.getArgument(0).hashCode()));
        when(redisson.<String>getScoredSortedSet(anyString(), any(StringCodec.class))).thenReturn(heartbeats);
        when(redisson.<String>getScoredSortedSet(anyString())).thenReturn(heartbeats);
        when(redisson.getTopic(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(mock(RTopic.class));
        when(heartbeats.valueRange(anyDouble(), anyBoolean(), anyDouble(), anyBoolean()))
                .thenReturn(List.of("i1"));
        when(heartbeats.getScore("i1")).thenReturn((double) System.currentTimeMillis());
        when(script.evalSha(eq(org.redisson.api.RScript.Mode.READ_ONLY), anyString(),
                eq(org.redisson.api.RScript.ReturnType.LIST), anyList(),
                any(), any(), any(), any(), any())).thenReturn(List.of("i1"));
    }

    @SuppressWarnings("unchecked")
    private void stubInstance(String serviceName, String instanceId) {
        RMap<String, String> map = mock(RMap.class);
        Map<String, String> data = new java.util.HashMap<>();
        data.put("host", "127.0.0.1");
        data.put("port", "1");
        data.put("protocol", "tcp");
        data.put("enabled", "true");
        data.put("healthy", "true");
        data.put("weight", "1");
        data.put("metadata", "{\"region\":\"cn\"}");
        when(map.readAllMap()).thenReturn(data);
        when(map.isExists()).thenReturn(true);
        when(redisson.<String, String>getMap(
                eq(new ServiceProviderConfig().getServiceInstanceKey(serviceName, instanceId)),
                any(StringCodec.class))).thenReturn(map);
        when(redisson.<String, String>getMap(anyString())).thenReturn(map);
    }

    @Test
    void registerSubscribeChooseAndUnsubscribeWork() {
        RedisNamingService naming = new RedisNamingService(redisson);
        assertThrows(IllegalStateException.class,
                () -> naming.register(instance("svc", "i1")));
        assertThrows(IllegalStateException.class,
                () -> naming.deregister(instance("svc", "i1")));
        naming.unsubscribe("svc", (serviceName, action, changed, instances) -> { }); // not running -> warn only

        naming.start();
        stubInstance("svc", "i1");
        naming.register(instance("svc", "i1"));
        naming.deregister(instance("svc", "i1"));

        List<ServiceInstance> byMeta = naming.discoverByMetadata("svc", Map.of("region", "cn"));
        assertEquals(1, byMeta.size());
        assertEquals(1, naming.discoverHealthyByMetadata("svc", Map.of("region", "cn")).size());
        assertEquals(1, naming.getInstancesByMetadata("svc", Map.of("region", "cn")).size());
        assertEquals(1, naming.getHealthyInstancesByMetadata("svc", Map.of("region", "cn")).size());

        LoadBalancer first = (svc, candidates, ctx) -> candidates.isEmpty() ? null : candidates.get(0);
        ServiceInstance chosen = naming.chooseHealthyInstance("svc", first, Map.of("hashKey", "k"));
        assertNotNull(chosen);
        assertNotNull(naming.chooseHealthyInstanceByFilters("svc", Map.of("a", "1"), Map.of("cpu", "1"),
                first, Map.of()));

        naming.getInstancesByFilters("svc", Map.of("a", "1"), Map.of("cpu", "1"));
        naming.getHealthyInstancesByFilters("svc", Map.of("a", "1"), null);

        io.github.cuihairu.redis.streaming.registry.listener.ServiceChangeListener l =
                (serviceName, action, changed, instances) -> { };
        naming.subscribe("svc", l);
        naming.unsubscribe("svc", l);

        naming.stop();
        naming.stop(); // warn only
        naming.sendHeartbeat(instance("svc", "i1")); // not running -> silent
        naming.batchSendHeartbeats(List.of());
        naming.batchHeartbeat(List.of(instance("svc", "i1")));
        naming.heartbeat(instance("svc", "i1"));
    }

    private static ServiceInstance instance(String serviceName, String instanceId) {
        return DefaultServiceInstance.builder()
                .serviceName(serviceName).instanceId(instanceId).host("127.0.0.1").port(1)
                .protocol(StandardProtocol.TCP).metadata(Map.of()).healthy(true).build();
    }
}
