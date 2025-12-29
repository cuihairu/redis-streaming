package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConsistentHashLoadBalancer
 */
class ConsistentHashLoadBalancerTest {

    private ConsistentHashLoadBalancer loadBalancer;

    @BeforeEach
    void setUp() {
        loadBalancer = new ConsistentHashLoadBalancer();
    }

    @Test
    void testDefaultConstructor() {
        ConsistentHashLoadBalancer lb = new ConsistentHashLoadBalancer();

        assertNotNull(lb);
    }

    @Test
    void testConstructorWithVirtualNodes() {
        ConsistentHashLoadBalancer lb = new ConsistentHashLoadBalancer(64);

        assertNotNull(lb);
    }

    @Test
    void testConstructorWithZeroVirtualNodes() {
        ConsistentHashLoadBalancer lb = new ConsistentHashLoadBalancer(0);

        assertNotNull(lb);
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

        ServiceInstance result = loadBalancer.choose("test-service", List.of(instance), Map.of());

        assertNotNull(result);
        assertEquals("instance1", result.getInstanceId());
    }

    @Test
    void testChooseWithoutHashKey() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        // Without hashKey, should return first candidate
        ServiceInstance result = loadBalancer.choose("service1", candidates, Map.of());

        assertNotNull(result);
        assertEquals("instance1", result.getInstanceId());
    }

    @Test
    void testChooseWithNullHashKey() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        Map<String, Object> context = new HashMap<>();
        context.put("hashKey", null);

        // With null hashKey, should return first candidate
        ServiceInstance result = loadBalancer.choose("service1", candidates, context);

        assertNotNull(result);
        assertEquals("instance1", result.getInstanceId());
    }

    @Test
    void testChooseWithHashKey() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        Map<String, Object> context = new HashMap<>();
        context.put("hashKey", "user123");

        ServiceInstance result = loadBalancer.choose("service1", candidates, context);

        assertNotNull(result);
        assertTrue(List.of("instance1", "instance2").contains(result.getInstanceId()));
    }

    @Test
    void testConsistentHashing() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        Map<String, Object> context = new HashMap<>();
        context.put("hashKey", "consistent-key");

        // Same key should always route to same instance
        ServiceInstance result1 = loadBalancer.choose("service1", candidates, context);
        ServiceInstance result2 = loadBalancer.choose("service1", candidates, context);
        ServiceInstance result3 = loadBalancer.choose("service1", candidates, context);

        assertNotNull(result1);
        assertEquals(result1.getInstanceId(), result2.getInstanceId());
        assertEquals(result2.getInstanceId(), result3.getInstanceId());
    }

    @Test
    void testDifferentKeysMayRouteToDifferentInstances() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        Map<String, Object> context1 = new HashMap<>();
        context1.put("hashKey", "key1");

        Map<String, Object> context2 = new HashMap<>();
        context2.put("hashKey", "key2");

        ServiceInstance result1 = loadBalancer.choose("service1", candidates, context1);
        ServiceInstance result2 = loadBalancer.choose("service1", candidates, context2);

        assertNotNull(result1);
        assertNotNull(result2);
        // May or may not be different depending on hash
        assertTrue(List.of("instance1", "instance2").contains(result1.getInstanceId()));
        assertTrue(List.of("instance1", "instance2").contains(result2.getInstanceId()));
    }

    @Test
    void testChooseWithMultipleCandidates() {
        List<ServiceInstance> candidates = List.of(
            new TestServiceInstance("service1", "instance1", "localhost", 8080),
            new TestServiceInstance("service1", "instance2", "localhost", 8081),
            new TestServiceInstance("service1", "instance3", "localhost", 8082),
            new TestServiceInstance("service1", "instance4", "localhost", 8083),
            new TestServiceInstance("service1", "instance5", "localhost", 8084)
        );

        Map<String, Object> context = new HashMap<>();
        context.put("hashKey", "test-key");

        ServiceInstance result = loadBalancer.choose("service1", candidates, context);

        assertNotNull(result);
        assertTrue(candidates.stream().anyMatch(i -> i.getInstanceId().equals(result.getInstanceId())));
    }

    @Test
    void testWithStringHashKey() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        Map<String, Object> context = new HashMap<>();
        context.put("hashKey", "string-key");

        ServiceInstance result = loadBalancer.choose("service1", candidates, context);

        assertNotNull(result);
    }

    @Test
    void testWithNumericHashKey() {
        TestServiceInstance instance1 = new TestServiceInstance("service1", "instance1", "localhost", 8080);
        TestServiceInstance instance2 = new TestServiceInstance("service1", "instance2", "localhost", 8081);

        List<ServiceInstance> candidates = List.of(instance1, instance2);

        Map<String, Object> context = new HashMap<>();
        context.put("hashKey", 12345);

        ServiceInstance result = loadBalancer.choose("service1", candidates, context);

        assertNotNull(result);
    }

    // Test helper class
    private static class TestServiceInstance implements ServiceInstance {
        private final String serviceName;
        private final String instanceId;
        private final String host;
        private final int port;
        private final Map<String, String> metadata = new HashMap<>();

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
            return true;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public int getWeight() {
            return 1;
        }
    }
}
