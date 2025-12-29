package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WeightedRoundRobinLoadBalancer
 */
class WeightedRoundRobinLoadBalancerTest {

    private WeightedRoundRobinLoadBalancer loadBalancer;

    @BeforeEach
    void setUp() {
        loadBalancer = new WeightedRoundRobinLoadBalancer();
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

        for (int i = 0; i < 20; i++) {
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
        instance1.setWeight(3);

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(1);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Run multiple times and count selections
        int count1 = 0;
        int count2 = 0;

        for (int i = 0; i < 20; i++) {
            ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());
            if (result.getInstanceId().equals("instance1")) {
                count1++;
            } else if (result.getInstanceId().equals("instance2")) {
                count2++;
            }
        }

        // instance1 should be selected approximately 3:1 ratio
        assertTrue(count1 > count2, "instance1 should be selected more often than instance2");
        assertTrue(count1 >= 10, "instance1 should be selected at least ~75% of the time");
    }

    @Test
    void testRoundRobinPattern() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance1.setWeight(3);

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(1);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Run multiple selections and verify the weighted pattern
        // With smooth weighted round robin and weights 3:1, we should get
        // approximately 3 selections of instance1 for every 1 of instance2
        int count1 = 0;
        int count2 = 0;

        for (int i = 0; i < 40; i++) {
            ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());
            if (result.getInstanceId().equals("instance1")) {
                count1++;
            } else {
                count2++;
            }
        }

        // Verify the 3:1 ratio (approximately)
        double ratio = (double) count1 / count2;
        assertTrue(ratio > 2.0 && ratio < 4.0, "Ratio should be approximately 3:1, was " + ratio);
        assertTrue(count1 > 25, "instance1 should be selected most of the time");
        assertTrue(count2 < 15, "instance2 should be selected less frequently");
    }

    @Test
    void testChooseWithMetadataWeight() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        instance1.setWeight(1);
        instance1.getMetadata().put("weight", "5");

        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        instance2.setWeight(5);
        instance2.getMetadata().put("weight", "1");

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Run multiple times and count selections
        int count1 = 0;
        int count2 = 0;

        for (int i = 0; i < 20; i++) {
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
    void testMultipleServicesIsolatedState() {
        TestServiceInstance service1Instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        service1Instance1.setWeight(3);

        TestServiceInstance service1Instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);
        service1Instance2.setWeight(1);

        TestServiceInstance service2Instance1 = new TestServiceInstance("service2", "instance1", "localhost", 8082);
        service2Instance1.setWeight(1);

        TestServiceInstance service2Instance2 = new TestServiceInstance("service2", "instance2", "localhost", 8083);
        service2Instance2.setWeight(3);

        List<ServiceInstance> service1Candidates = List.of(service1Instance1, service1Instance2);
        List<ServiceInstance> service2Candidates = List.of(service2Instance1, service2Instance2);

        // service1 should select instance1 more often
        int service1Count1 = 0;
        int service1Count2 = 0;

        for (int i = 0; i < 20; i++) {
            ServiceInstance result = loadBalancer.choose("service1", service1Candidates, Map.of());
            if (result.getInstanceId().equals("instance1")) {
                service1Count1++;
            } else {
                service1Count2++;
            }
        }

        // service2 should select instance2 more often
        int service2Count1 = 0;
        int service2Count2 = 0;

        for (int i = 0; i < 20; i++) {
            ServiceInstance result = loadBalancer.choose("service2", service2Candidates, Map.of());
            if (result.getInstanceId().equals("instance1")) {
                service2Count1++;
            } else {
                service2Count2++;
            }
        }

        assertTrue(service1Count1 > service1Count2, "service1 instance1 should have higher count");
        assertTrue(service2Count2 > service2Count1, "service2 instance2 should have higher count");
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

        // Context is ignored by WeightedRoundRobinLoadBalancer but should not cause errors
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
