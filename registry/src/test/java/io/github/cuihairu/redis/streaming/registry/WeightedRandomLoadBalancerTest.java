package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRandomLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class WeightedRandomLoadBalancerTest {

    @Test
    void testRoughWeightDistribution() {
        String svc = "svc";
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(5).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2).weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 5000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }
        // Expect about 5:1; give generous bounds
        assertTrue(ca > 3500 && cb < 1500, "distribution not roughly 5:1 -> a=" + ca + ", b=" + cb);
    }

    @Test
    void chooseReturnsNullForEmptyList() {
        var lb = new WeightedRandomLoadBalancer();
        ServiceInstance result = lb.choose("service", List.of(), Map.of());
        assertNull(result);
    }

    @Test
    void chooseReturnsNullForNullList() {
        var lb = new WeightedRandomLoadBalancer();
        ServiceInstance result = lb.choose("service", null, Map.of());
        assertNull(result);
    }

    @Test
    void chooseReturnsSingleInstanceForOneElementList() {
        String svc = "svc";
        var instance = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(5).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(instance);

        ServiceInstance result = lb.choose(svc, list, Map.of());
        assertSame(instance, result);
    }

    @Test
    void chooseWithEqualWeights() {
        String svc = "svc";
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(1).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2).weight(1).build();
        var c = DefaultServiceInstance.builder().serviceName(svc).instanceId("c").host("127.0.0.3").port(3).weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b, c);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 3000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            counts.merge(s.getInstanceId(), 1, Integer::sum);
        }

        // All should have roughly equal counts (~1000 each)
        assertTrue(counts.get("a") > 800 && counts.get("a") < 1200,
                "a count not roughly equal: " + counts.get("a"));
        assertTrue(counts.get("b") > 800 && counts.get("b") < 1200,
                "b count not roughly equal: " + counts.get("b"));
        assertTrue(counts.get("c") > 800 && counts.get("c") < 1200,
                "c count not roughly equal: " + counts.get("c"));
    }

    @Test
    void chooseWithZeroWeightDefaultsToOne() {
        String svc = "svc";
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(0).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2).weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 2000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }

        // Zero weight should default to 1, so roughly 1:1 distribution
        assertTrue(ca > 700 && ca < 1300, "a count not roughly 1:1: " + ca);
        assertTrue(cb > 700 && cb < 1300, "b count not roughly 1:1: " + cb);
    }

    @Test
    void chooseWithNegativeWeightDefaultsToOne() {
        String svc = "svc";
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(-5).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2).weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 2000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }

        // Negative weight should default to 1, so roughly 1:1 distribution
        assertTrue(ca > 700 && ca < 1300, "a count not roughly 1:1: " + ca);
        assertTrue(cb > 700 && cb < 1300, "b count not roughly 1:1: " + cb);
    }

    @Test
    void chooseWithMetadataWeightOverride() {
        String svc = "svc";
        Map<String, String> metaA = Map.of("weight", "10");
        Map<String, String> metaB = Map.of("weight", "1");
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1)
                .weight(1).metadata(metaA).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2)
                .weight(1).metadata(metaB).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 2000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }

        // Metadata weight should override, giving 10:1 distribution
        assertTrue(ca > 1500, "a should be chosen much more often: " + ca);
        assertTrue(cb < 500, "b should be chosen much less often: " + cb);
    }

    @Test
    void chooseWithInvalidMetadataWeight() {
        String svc = "svc";
        Map<String, String> metaA = Map.of("weight", "invalid");
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1)
                .weight(5).metadata(metaA).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2)
                .weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 1000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }

        // Invalid metadata weight should fall back to instance weight (5:1)
        assertTrue(ca > 700 && cb < 300, "a should have 5:1 ratio with invalid metadata: a=" + ca + ", b=" + cb);
    }

    @Test
    void chooseWithLargeWeightDifference() {
        String svc = "svc";
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(100).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2).weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 1000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }

        // a should be chosen almost all the time
        assertTrue(ca > 950, "a should be chosen >95% of the time: " + ca);
        assertTrue(cb < 50, "b should be chosen <5% of the time: " + cb);
    }

    @Test
    void chooseWithManyCandidates() {
        String svc = "svc";
        List<ServiceInstance> instances = List.of(
                DefaultServiceInstance.builder().serviceName(svc).instanceId("1").host("127.0.0.1").port(1).weight(1).build(),
                DefaultServiceInstance.builder().serviceName(svc).instanceId("2").host("127.0.0.2").port(2).weight(1).build(),
                DefaultServiceInstance.builder().serviceName(svc).instanceId("3").host("127.0.0.3").port(3).weight(1).build(),
                DefaultServiceInstance.builder().serviceName(svc).instanceId("4").host("127.0.0.4").port(4).weight(1).build(),
                DefaultServiceInstance.builder().serviceName(svc).instanceId("5").host("127.0.0.5").port(5).weight(1).build(),
                DefaultServiceInstance.builder().serviceName(svc).instanceId("6").host("127.0.0.6").port(6).weight(1).build(),
                DefaultServiceInstance.builder().serviceName(svc).instanceId("7").host("127.0.0.7").port(7).weight(1).build(),
                DefaultServiceInstance.builder().serviceName(svc).instanceId("8").host("127.0.0.8").port(8).weight(1).build(),
                DefaultServiceInstance.builder().serviceName(svc).instanceId("9").host("127.0.0.9").port(9).weight(1).build(),
                DefaultServiceInstance.builder().serviceName(svc).instanceId("10").host("127.0.0.10").port(10).weight(1).build()
        );
        var lb = new WeightedRandomLoadBalancer();

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 5000; i++) {
            ServiceInstance s = lb.choose(svc, instances, Map.of());
            counts.merge(s.getInstanceId(), 1, Integer::sum);
        }

        // All should have some selections
        assertEquals(10, counts.size(), "All instances should be selected at least once");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            assertTrue(entry.getValue() > 200, "Instance " + entry.getKey() + " has too few selections: " + entry.getValue());
            assertTrue(entry.getValue() < 800, "Instance " + entry.getKey() + " has too many selections: " + entry.getValue());
        }
    }

    @Test
    void chooseWithThreeCandidatesWeightDistribution() {
        String svc = "svc";
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(2).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2).weight(3).build();
        var c = DefaultServiceInstance.builder().serviceName(svc).instanceId("c").host("127.0.0.3").port(3).weight(5).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b, c);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 5000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            counts.merge(s.getInstanceId(), 1, Integer::sum);
        }

        // Roughly 2:3:5 ratio
        assertTrue(counts.get("a") > 800 && counts.get("a") < 1200,
                "a count not roughly 20%: " + counts.get("a"));
        assertTrue(counts.get("b") > 1300 && counts.get("b") < 1700,
                "b count not roughly 30%: " + counts.get("b"));
        assertTrue(counts.get("c") > 2300 && counts.get("c") < 2700,
                "c count not roughly 50%: " + counts.get("c"));
    }

    @Test
    void chooseIgnoresContext() {
        String svc = "svc";
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(10).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2).weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        Map<String, Object> context = Map.of("key", "value");
        int ca = 0, cb = 0;
        for (int i = 0; i < 500; i++) {
            ServiceInstance s = lb.choose(svc, list, context);
            if (s == a) ca++; else if (s == b) cb++;
        }

        // Context should not affect the distribution
        assertTrue(ca > 350 && cb < 150, "Context affected distribution: a=" + ca + ", b=" + cb);
    }

    @Test
    void chooseWithVerySmallWeights() {
        String svc = "svc";
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(1).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2).weight(2).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 3000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }

        // Roughly 1:2 ratio
        assertTrue(ca > 800 && ca < 1200, "a count not roughly 1:2: " + ca);
        assertTrue(cb > 1800 && cb < 2200, "b count not roughly 1:2: " + cb);
    }

    @Test
    void chooseDeterministicBehaviorWithSingleCandidate() {
        String svc = "svc";
        var instance = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(100).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(instance);

        // Always return the same instance
        for (int i = 0; i < 100; i++) {
            ServiceInstance result = lb.choose(svc, list, Map.of());
            assertSame(instance, result);
        }
    }

    @Test
    void chooseWithMetadataZeroWeight() {
        String svc = "svc";
        Map<String, String> metaA = Map.of("weight", "0");
        Map<String, String> metaB = Map.of("weight", "1");
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1)
                .weight(10).metadata(metaA).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2)
                .weight(10).metadata(metaB).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 2000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }

        // Metadata weight 0 should default to 1
        assertTrue(ca > 700 && ca < 1300, "a with metadata weight 0 should default to 1: " + ca);
        assertTrue(cb > 700 && cb < 1300, "b with metadata weight 1 should be 1: " + cb);
    }

    @Test
    void chooseWithMetadataNegativeWeight() {
        String svc = "svc";
        Map<String, String> metaA = Map.of("weight", "-5");
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1)
                .weight(10).metadata(metaA).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2)
                .weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 2000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }

        // Metadata weight -5 should default to 1
        assertTrue(ca > 700 && ca < 1300, "a with metadata weight -5 should default to 1: " + ca);
        assertTrue(cb > 700 && cb < 1300, "b should also default to 1: " + cb);
    }

    @Test
    void chooseWithEmptyMetadata() {
        String svc = "svc";
        Map<String, String> emptyMeta = Map.of();
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1)
                .weight(5).metadata(emptyMeta).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2)
                .weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b);

        int ca = 0, cb = 0;
        for (int i = 0; i < 1000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            if (s == a) ca++; else if (s == b) cb++;
        }

        // Should use instance weight (5:1) when metadata is empty
        assertTrue(ca > 700 && cb < 300, "a should have 5:1 ratio with empty metadata: a=" + ca + ", b=" + cb);
    }

    @Test
    void chooseAllInstancesEventuallySelected() {
        String svc = "svc";
        var a = DefaultServiceInstance.builder().serviceName(svc).instanceId("a").host("127.0.0.1").port(1).weight(1).build();
        var b = DefaultServiceInstance.builder().serviceName(svc).instanceId("b").host("127.0.0.2").port(2).weight(100).build();
        var c = DefaultServiceInstance.builder().serviceName(svc).instanceId("c").host("127.0.0.3").port(3).weight(1).build();
        var lb = new WeightedRandomLoadBalancer();
        List<ServiceInstance> list = List.of(a, b, c);

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 5000; i++) {
            ServiceInstance s = lb.choose(svc, list, Map.of());
            counts.merge(s.getInstanceId(), 1, Integer::sum);
        }

        // Even with skewed weights, all should be selected
        assertEquals(3, counts.size(), "All instances should be selected");
        assertTrue(counts.get("a") > 0, "a should be selected at least once");
        assertTrue(counts.get("c") > 0, "c should be selected at least once");
    }
}
