package io.github.cuihairu.redis.streaming.registry;

import io.github.cuihairu.redis.streaming.registry.loadbalancer.ConsistentHashLoadBalancer;
import io.github.cuihairu.redis.streaming.registry.loadbalancer.WeightedRoundRobinLoadBalancer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LoadBalancerUnitTest {
    private static ServiceInstance ins(String svc, String id, int weight) {
        return DefaultServiceInstance.builder()
                .serviceName(svc)
                .instanceId(id)
                .host("127.0.0.1")
                .port(8000)
                .weight(weight)
                .build();
    }

    @Test
    public void testWeightedRoundRobin() {
        String svc = "foo";
        var wrr = new WeightedRoundRobinLoadBalancer();
        var a = ins(svc, "a", 5);
        var b = ins(svc, "b", 1);
        var list = List.of(a,b);

        int ca=0, cb=0;
        for (int i=0;i<600;i++) {
            ServiceInstance s = wrr.choose(svc, list, Map.of());
            if (s==a) ca++; else if (s==b) cb++;
        }
        // rough ratio 5:1
        assertTrue(ca > 450 && cb < 150, "WRR ratio not roughly respected: "+ca+":"+cb);
    }

    @Test
    public void testConsistentHashStability() {
        String svc = "foo";
        var ch = new ConsistentHashLoadBalancer(64);
        var a = ins(svc, "a", 1);
        var b = ins(svc, "b", 1);
        var list = List.of(a,b);

        String key = "user:12345";
        ServiceInstance s1 = ch.choose(svc, list, Map.of("hashKey", key));
        ServiceInstance s2 = ch.choose(svc, list, Map.of("hashKey", key));
        assertNotNull(s1); assertNotNull(s2);
        assertEquals(s1.getInstanceId(), s2.getInstanceId(), "Consistent hash should be stable for same key");
    }
}

