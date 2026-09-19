package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.DefaultServiceInstance;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import io.github.cuihairu.redis.streaming.registry.StandardProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoredLoadBalancerTest {

    private final MetricsProvider provider = mock(MetricsProvider.class);

    private ServiceInstance ins(String id, int weight, Map<String, String> meta) {
        return DefaultServiceInstance.builder()
                .serviceName("svc").instanceId(id).host("h").port(80)
                .protocol(StandardProtocol.HTTP).weight(weight).metadata(meta).build();
    }

    @Test
    void trivialCandidates() {
        ScoredLoadBalancer lb = new ScoredLoadBalancer(new LoadBalancerConfig(), provider);
        assertNull(lb.choose("svc", null, null));
        assertNull(lb.choose("svc", List.of(), null));
        ServiceInstance only = ins("a", 1, Map.of());
        assertSame(only, lb.choose("svc", List.of(only), null));
    }

    @Test
    void nullConfigDefaultsAndMissingMetricsAreSafe() {
        when(provider.getMetrics(anyString(), anyString())).thenReturn(Map.of());
        ScoredLoadBalancer lb = new ScoredLoadBalancer(null, provider);
        ServiceInstance a = ins("a", 1, Map.of());
        ServiceInstance b = ins("b", 1, Map.of());
        assertNotNull(lb.choose("svc", List.of(a, b), null));
    }

    @Test
    void higherWeightWins() {
        when(provider.getMetrics(anyString(), anyString())).thenReturn(Map.of());
        ScoredLoadBalancer lb = new ScoredLoadBalancer(new LoadBalancerConfig(), provider);
        ServiceInstance heavy = ins("heavy", 100, Map.of());
        ServiceInstance light = ins("light", 1, Map.of());
        assertSame(heavy, lb.choose("svc", List.of(light, heavy), null));
    }

    @Test
    void metadataWeightOverridesFieldAndGarbageFallsBack() {
        when(provider.getMetrics(anyString(), anyString())).thenReturn(Map.of());
        ScoredLoadBalancer lb = new ScoredLoadBalancer(new LoadBalancerConfig(), provider);
        ServiceInstance boosted = ins("boosted", 1, Map.of("weight", "999"));
        ServiceInstance broken = ins("broken", 50, Map.of("weight", "not-a-number"));
        assertSame(boosted, lb.choose("svc", List.of(broken, boosted), null));
    }

    @Test
    void localityBoostChangesDecision() {
        when(provider.getMetrics(anyString(), anyString())).thenReturn(Map.of());
        LoadBalancerConfig cfg = new LoadBalancerConfig();
        cfg.setPreferredRegion("east");
        cfg.setRegionBoost(3.0);
        ScoredLoadBalancer lb = new ScoredLoadBalancer(cfg, provider);
        ServiceInstance east = ins("east", 1, Map.of("region", "east"));
        ServiceInstance west = ins("west", 1, Map.of("region", "west"));
        assertSame(east, lb.choose("svc", List.of(west, east), null));

        cfg.setPreferredZone("z9");
        cfg.setZoneBoost(9.0);
        ScoredLoadBalancer lb2 = new ScoredLoadBalancer(cfg, provider);
        ServiceInstance z9 = ins("z9", 1, Map.of("zone", "z9"));
        ServiceInstance plain = ins("plain", 1, Map.of());
        assertSame(z9, lb2.choose("svc", List.of(plain, z9), null));
    }

    @Test
    void hardConstraintsExcludeOverloadedButFallbackKeepsOne() {
        LoadBalancerConfig cfg = new LoadBalancerConfig();
        cfg.setMaxCpuPercent(80);
        cfg.setMaxLatencyMs(100);
        cfg.setMaxMemoryPercent(90);
        cfg.setMaxInflight(50);
        cfg.setMaxQueue(100);
        cfg.setMaxErrorRatePercent(5);
        ScoredLoadBalancer lb = new ScoredLoadBalancer(cfg, provider);
        when(provider.getMetrics("svc", "hot")).thenReturn(Map.of(
                "cpu", 99.0, "latency", 500.0, "memory", 99.0, "inflight", 999.0, "queue", 9999.0, "errorRate", 90.0));
        when(provider.getMetrics("svc", "cool")).thenReturn(Map.of(
                "cpu", 5.0, "latency", 2.0, "memory", 10.0, "inflight", 1.0, "queue", 2.0, "errorRate", 0.1));
        ServiceInstance hot = ins("hot", 9, Map.of());
        ServiceInstance cool = ins("cool", 1, Map.of());
        assertSame(cool, lb.choose("svc", List.of(hot, cool), null));

        // all candidates exceed limits -> fallback keeps the first one
        when(provider.getMetrics("svc", "hot2")).thenReturn(Map.of(
                "cpu", 99.0, "latency", 500.0, "memory", 99.0, "inflight", 999.0, "queue", 9999.0, "errorRate", 90.0));
        ServiceInstance hot2 = ins("hot2", 9, Map.of());
        assertSame(hot, lb.choose("svc", List.of(hot, hot2), null));

        // numeric string metrics are parsed
        when(provider.getMetrics("svc", "str")).thenReturn(Map.of("cpu", "42"));
        assertNotNull(lb.choose("svc", List.of(ins("str", 1, Map.of()), cool), null));
    }

    @Test
    void scoringWeightsWithAllFactors() {
        LoadBalancerConfig cfg = new LoadBalancerConfig();
        cfg.setCpuWeight(2.0);
        cfg.setLatencyWeight(1.5);
        cfg.setMemoryWeight(1.0);
        cfg.setInflightWeight(1.0);
        cfg.setQueueWeight(1.0);
        cfg.setErrorRateWeight(2.0);
        cfg.setTargetLatencyMs(10);
        ScoredLoadBalancer lb = new ScoredLoadBalancer(cfg, provider);
        when(provider.getMetrics("svc", "busy")).thenReturn(Map.of(
                "cpu", 70.0, "latency", 80.0, "memory", 60.0, "inflight", 20.0, "queue", 30.0, "errorRate", 3.0));
        when(provider.getMetrics("svc", "idle")).thenReturn(Map.of(
                "cpu", 3.0, "latency", 1.0, "memory", 5.0, "inflight", 0.0, "queue", 0.0, "errorRate", 0.0));
        ServiceInstance idle = ins("idle", 5, Map.of());
        ServiceInstance busy = ins("busy", 5, Map.of());
        // deterministic: idle scores better regardless of candidate order
        assertEquals("idle", lb.choose("svc", List.of(idle, busy), null).getInstanceId());
        assertEquals("idle", lb.choose("svc", List.of(busy, idle), null).getInstanceId());
    }
}
