package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRandomLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.junit.jupiter.api.Test;

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
}
