package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Covers ConsistentHashLoadBalancer ring construction with null unique IDs,
 * ScoredLoadBalancer.getDouble fallbacks and RedisMetricsProvider read paths.
 */
class LoadBalancersCoverageTest {

    private static ServiceInstance ins(String svc, String id) {
        return DefaultServiceInstance.builder()
                .serviceName(svc).instanceId(id).host("127.0.0.1").port(1)
                .protocol(StandardProtocol.TCP).metadata(Map.of()).healthy(true).build();
    }

    /** Instance whose getUniqueId() is null so buildRing falls back to name+id base. */
    private static ServiceInstance nullUniqueIdIns(String id) {
        return new ServiceInstance() {
            @Override
            public String getServiceName() {
                return null;
            }

            @Override
            public String getInstanceId() {
                return id;
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
                return Collections.emptyMap();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public boolean isHealthy() {
                return true;
            }
        };
    }

    @Test
    void consistentHashCoversNullUniqueIdBaseAndRingSelection() {
        ConsistentHashLoadBalancer lb = new ConsistentHashLoadBalancer(8);
        List<ServiceInstance> candidates = List.of(nullUniqueIdIns("a"), nullUniqueIdIns("b"), ins("s", "c"));

        // null context and literal "null" key fall back to the first candidate
        assertEquals(candidates.get(0), lb.choose("s", candidates, null));
        assertEquals(candidates.get(0), lb.choose("s", candidates, Map.of("hashKey", "null")));

        // ring-based selection: same key always maps to the same instance
        for (int i = 0; i < 200; i++) {
            Map<String, Object> ctx = Map.of("hashKey", "key-" + i);
            ServiceInstance picked = lb.choose("s", candidates, ctx);
            assertNotNull(picked);
            assertEquals(picked, lb.choose("s", candidates, ctx));
        }

        // degenerate candidate lists
        assertNull(lb.choose("s", List.of(), Map.of("hashKey", "k")));
        assertNull(lb.choose("s", null, Map.of("hashKey", "k")));
        assertEquals(candidates.get(0), lb.choose("s", List.of(candidates.get(0)), Map.of("hashKey", "k")));
    }

    @Test
    void scoredLoadBalancerGetDoubleFallsBackOnGarbageValues() {
        MetricsProvider garbage = (serviceName, instanceId) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("cpu", "not-a-number");
            m.put("latency", "??");
            return m;
        };
        ScoredLoadBalancer lb = new ScoredLoadBalancer(new LoadBalancerConfig(), garbage);
        ServiceInstance a = ins("s", "a");
        ServiceInstance b = ins("s", "b");
        ServiceInstance picked = lb.choose("s", List.of(a, b), Map.of());
        assertNotNull(picked);

        MetricsProvider numeric = (serviceName, instanceId) -> Map.of("cpu", 10, "latency", 5L);
        assertNotNull(new ScoredLoadBalancer(new LoadBalancerConfig(), numeric).choose("s", List.of(a, b), Map.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisMetricsProviderReadsParsesAndCaches() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RMap<String, String> map = mock(RMap.class);
        when(redisson.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class))).thenReturn(map);
        when(map.get("metrics")).thenReturn("{\"cpu\":0.5}").thenReturn("").thenReturn(null);

        io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig cfg =
                new io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig();
        RedisMetricsProvider provider = new RedisMetricsProvider(redisson, cfg, 60_000);

        // parse success
        Map<String, Object> parsed = provider.getMetrics("s", "i");
        assertEquals(0.5, ((Number) parsed.get("cpu")).doubleValue());
        // cache hit within TTL
        assertEquals(parsed, provider.getMetrics("s", "i"));

        // empty and missing metrics JSON -> empty map (cached briefly)
        RedisMetricsProvider provider2 = new RedisMetricsProvider(redisson, cfg, 1);
        assertTrue(provider2.getMetrics("s2", "i").isEmpty());
        Thread.sleep(5);
        assertTrue(provider2.getMetrics("s2", "i").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisMetricsProviderCachesFailures() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenThrow(new IllegalStateException("redis down"));
        io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig cfg =
                new io.github.cuihairu.redis.streaming.registry.ServiceConsumerConfig();
        RedisMetricsProvider provider = new RedisMetricsProvider(redisson, cfg);
        assertTrue(provider.getMetrics("s", "i").isEmpty());
    }
}
