package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.Protocol;
import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WeightedRandomLoadBalancer
 */
class WeightedRandomLoadBalancerTest {

    private WeightedRandomLoadBalancer loadBalancer;

    @BeforeEach
    void setUp() {
        loadBalancer = new WeightedRandomLoadBalancer();
    }

    @Test
    void testChooseWithNullCandidates() {
        ServiceInstance result = loadBalancer.choose("test-service", null, Map.of());

        assertNull(result);
    }

    @Test
    void testChooseWithEmptyCandidates() {
        ServiceInstance result = loadBalancer.choose("test-service", List.of(), Map.of());

        assertNull(result);
    }

    @Test
    void testChooseWithSingleCandidate() {
        TestServiceInstance instance = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance.setWeight(5);

        ServiceInstance result = loadBalancer.choose("test-service", List.of(instance), Map.of());

        assertNotNull(result);
        assertEquals("instance1", result.getInstanceId());
    }

    @Test
    void testChooseWithEqualWeights() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance1.setWeight(1);

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(1);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Run multiple times to ensure both can be selected
        boolean selected1 = false;
        boolean selected2 = false;

        for (int i = 0; i < 100; i++) {
            ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());
            if (result.getInstanceId().equals("instance1")) {
                selected1 = true;
            } else if (result.getInstanceId().equals("instance2")) {
                selected2 = true;
            }
        }

        assertTrue(selected1, "instance1 should be selected at least once");
        assertTrue(selected2, "instance2 should be selected at least once");
    }

    @Test
    void testChooseWithDifferentWeights() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance1.setWeight(10);

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(1);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Run multiple times and count selections
        int count1 = 0;
        int count2 = 0;

        for (int i = 0; i < 100; i++) {
            ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());
            if (result.getInstanceId().equals("instance1")) {
                count1++;
            } else if (result.getInstanceId().equals("instance2")) {
                count2++;
            }
        }

        // instance1 should be selected more often (approximately 10:1 ratio)
        assertTrue(count1 > count2, "instance1 should be selected more often than instance2");
        assertTrue(count1 > 50, "instance1 should be selected more than 50% of the time");
        assertTrue(count2 < 50, "instance2 should be selected less than 50% of the time");
    }

    @Test
    void testChooseWithMetadataWeight() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance1.setWeight(1);
        instance1.getMetadata().put("weight", "100");

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(100);
        instance2.getMetadata().put("weight", "1");

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Run multiple times and count selections
        int count1 = 0;
        int count2 = 0;

        for (int i = 0; i < 100; i++) {
            ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());
            if (result.getInstanceId().equals("instance1")) {
                count1++;
            } else if (result.getInstanceId().equals("instance2")) {
                count2++;
            }
        }

        // instance1 should be selected more often (metadata weight overrides getWeight())
        assertTrue(count1 > count2, "instance1 should be selected more often due to higher metadata weight");
    }

    @Test
    void testChooseWithZeroWeight() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance1.setWeight(0); // Should be treated as 1

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(1);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Should not throw and should select one of them
        ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());

        assertNotNull(result);
        assertTrue(List.of("instance1", "instance2").contains(result.getInstanceId()));
    }

    @Test
    void testChooseWithNegativeWeight() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance1.setWeight(-5); // Should be treated as 1

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(1);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Should not throw and should select one of them
        ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());

        assertNotNull(result);
        assertTrue(List.of("instance1", "instance2").contains(result.getInstanceId()));
    }

    @Test
    void testChooseWithInvalidMetadataWeight() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance1.setWeight(1);
        instance1.getMetadata().put("weight", "invalid"); // Should fall back to getWeight()

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(1);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Should not throw and should select one of them
        ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());

        assertNotNull(result);
        assertTrue(List.of("instance1", "instance2").contains(result.getInstanceId()));
    }

    @Test
    void testChooseWithManyCandidates() {
        List<ServiceInstance> candidates = List.of(
            new TestServiceInstance("service1", "instance1", "localhost", 8080),
            new TestServiceInstance("service1", "instance2", "localhost", 8081),
            new TestServiceInstance("service1", "instance3", "localhost", 8082),
            new TestServiceInstance("service1", "instance4", "localhost", 8083),
            new TestServiceInstance("service1", "instance5", "localhost", 8084)
        );

        // Run multiple times
        for (int i = 0; i < 50; i++) {
            ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());

            assertNotNull(result);
            assertTrue(candidates.contains(result));
        }
    }

    @Test
    void testChooseWithContext() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance1.setWeight(1);

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(1);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        Map<String, Object> context = new HashMap<>();
        context.put("key1", "value1");

        // Context is ignored by WeightedRandomLoadBalancer but should not cause errors
        ServiceInstance result = loadBalancer.choose("service1", candidates, context);

        assertNotNull(result);
        assertTrue(List.of("instance1", "instance2").contains(result.getInstanceId()));
    }

    // Test helper class
    private static class TestServiceInstance implements ServiceInstance {
        private final String serviceName;
        private final String instanceId;
        private final String host;
        private final int port;
        private final Map<String, String> metadata = new HashMap<>();
        private int weight = 1;
        private boolean enabled = true;
        private boolean healthy = true;

        TestServiceInstance(String serviceName, String instanceId, String host, int port) {
            this.serviceName = serviceName;
            this.instanceId = instanceId;
            this.host = host;
            this.port = port;
        }

        @Override
        public String getServiceName() {
            return serviceName;
        }

        @Override
        public String getInstanceId() {
            return instanceId;
        }

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public Map<String, String> getMetadata() {
            return metadata;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public boolean isHealthy() {
            return healthy;
        }

        @Override
        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }
    }
}
