package io.github.cuihairu.redis.streaming.registry.loadbalancer;

import io.github.cuihairu.redis.streaming.registry.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LoadBalancer interface
 */
class LoadBalancerTest {

    @Test
    void testInterfaceHasChooseMethod() throws NoSuchMethodException {
        // Given & When & Then
        assertNotNull(LoadBalancer.class.getMethod("choose", String.class, List.class, Map.class));
    }

    @Test
    void testSimpleImplementation() {
        // Given
        LoadBalancer loadBalancer = (serviceName, candidates, context) -> {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            return candidates.get(0);
        };

        List<ServiceInstance> instances = createTestInstances(3);

        // When
        ServiceInstance chosen = loadBalancer.choose("test-service", instances, new HashMap<>());

        // Then
        assertNotNull(chosen);
        assertEquals("instance-0", chosen.getInstanceId());
    }

    @Test
    void testChooseFromEmptyList() {
        // Given
        LoadBalancer loadBalancer = (serviceName, candidates, context) -> {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            return candidates.get(0);
        };

        List<ServiceInstance> emptyList = new ArrayList<>();

        // When
        ServiceInstance chosen = loadBalancer.choose("test-service", emptyList, new HashMap<>());

        // Then
        assertNull(chosen);
    }

    @Test
    void testChooseFromNullList() {
        // Given
        LoadBalancer loadBalancer = (serviceName, candidates, context) -> {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            return candidates.get(0);
        };

        // When
        ServiceInstance chosen = loadBalancer.choose("test-service", null, new HashMap<>());

        // Then
        assertNull(chosen);
    }

    @Test
    void testChooseWithServiceName() {
        // Given - load balancer that uses service name in decision
        LoadBalancer loadBalancer = (serviceName, candidates, context) -> {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            // Use service name to select instance
            int index = serviceName.hashCode() % candidates.size();
            return candidates.get(Math.abs(index));
        };

        List<ServiceInstance> instances = createTestInstances(3);

        // When - same service should always return same instance
        ServiceInstance chosen1 = loadBalancer.choose("my-service", instances, new HashMap<>());
        ServiceInstance chosen2 = loadBalancer.choose("my-service", instances, new HashMap<>());

        // Then
        assertNotNull(chosen1);
        assertEquals(chosen1.getInstanceId(), chosen2.getInstanceId());

        // Different service should get different instance (likely)
        ServiceInstance chosen3 = loadBalancer.choose("different-service", instances, new HashMap<>());
        // May or may not be different due to hash collision
        assertNotNull(chosen3);
    }

    @Test
    void testChooseWithContext() {
        // Given - load balancer that uses context in decision
        LoadBalancer loadBalancer = (serviceName, candidates, context) -> {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            if (context != null && context.containsKey("preferredIndex")) {
                int index = (Integer) context.get("preferredIndex");
                if (index >= 0 && index < candidates.size()) {
                    return candidates.get(index);
                }
            }
            return candidates.get(0);
        };

        List<ServiceInstance> instances = createTestInstances(3);
        Map<String, Object> context = new HashMap<>();
        context.put("preferredIndex", 2);

        // When
        ServiceInstance chosen = loadBalancer.choose("test-service", instances, context);

        // Then
        assertEquals("instance-2", chosen.getInstanceId());
    }

    @Test
    void testRoundRobinImplementation() {
        // Given
        LoadBalancer loadBalancer = new LoadBalancer() {
            private int currentIndex = 0;

            @Override
            public ServiceInstance choose(String serviceName, List<ServiceInstance> candidates, Map<String, Object> context) {
                if (candidates == null || candidates.isEmpty()) {
                    return null;
                }
                int index = currentIndex % candidates.size();
                currentIndex++;
                return candidates.get(index);
            }
        };

        List<ServiceInstance> instances = createTestInstances(3);

        // When
        ServiceInstance first = loadBalancer.choose("test-service", instances, new HashMap<>());
        ServiceInstance second = loadBalancer.choose("test-service", instances, new HashMap<>());
        ServiceInstance third = loadBalancer.choose("test-service", instances, new HashMap<>());
        ServiceInstance fourth = loadBalancer.choose("test-service", instances, new HashMap<>());

        // Then - should cycle through instances
        assertEquals("instance-0", first.getInstanceId());
        assertEquals("instance-1", second.getInstanceId());
        assertEquals("instance-2", third.getInstanceId());
        assertEquals("instance-0", fourth.getInstanceId()); // back to start
    }

    @Test
    void testRandomImplementation() {
        // Given
        LoadBalancer loadBalancer = (serviceName, candidates, context) -> {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            return candidates.get((int) (Math.random() * candidates.size()));
        };

        List<ServiceInstance> instances = createTestInstances(10);

        // When
        ServiceInstance chosen = loadBalancer.choose("test-service", instances, new HashMap<>());

        // Then
        assertNotNull(chosen);
        assertTrue(instances.contains(chosen));
    }

    @Test
    void testImplementationWithNullHandling() {
        // Given
        LoadBalancer loadBalancer = new LoadBalancer() {
            @Override
            public ServiceInstance choose(String serviceName, List<ServiceInstance> candidates, Map<String, Object> context) {
                // Handle null parameters gracefully
                if (candidates == null || candidates.isEmpty()) {
                    return null;
                }
                if (context == null) {
                    context = new HashMap<>();
                }
                return candidates.get(0);
            }
        };

        List<ServiceInstance> instances = createTestInstances(2);

        // When & Then - should handle null context
        assertDoesNotThrow(() -> {
            ServiceInstance result = loadBalancer.choose("test-service", instances, null);
            assertNotNull(result);
        });
    }

    @Test
    void testContextAwareImplementation() {
        // Given - load balancer that uses context to make decisions
        LoadBalancer loadBalancer = (serviceName, candidates, context) -> {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            if (context != null && context.containsKey("preferredInstanceId")) {
                String preferredId = (String) context.get("preferredInstanceId");
                for (ServiceInstance instance : candidates) {
                    if (instance.getInstanceId().equals(preferredId)) {
                        return instance;
                    }
                }
            }
            return candidates.get(0); // fallback to first
        };

        List<ServiceInstance> instances = createTestInstances(3);
        Map<String, Object> context = new HashMap<>();
        context.put("preferredInstanceId", "instance-1");

        // When
        ServiceInstance chosen = loadBalancer.choose("test-service", instances, context);

        // Then
        assertEquals("instance-1", chosen.getInstanceId());
    }

    @Test
    void testMultipleInvocations() {
        // Given
        LoadBalancer loadBalancer = (serviceName, candidates, context) -> {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            return candidates.get(0);
        };

        List<ServiceInstance> instances = createTestInstances(5);

        // When - multiple calls with same input
        ServiceInstance result1 = loadBalancer.choose("service1", instances, new HashMap<>());
        ServiceInstance result2 = loadBalancer.choose("service1", instances, new HashMap<>());
        ServiceInstance result3 = loadBalancer.choose("service1", instances, new HashMap<>());

        // Then - should return same instance each time (deterministic)
        assertEquals(result1.getInstanceId(), result2.getInstanceId());
        assertEquals(result2.getInstanceId(), result3.getInstanceId());
    }

    @Test
    void testDifferentServices() {
        // Given
        LoadBalancer loadBalancer = (serviceName, candidates, context) -> {
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            return candidates.get(0);
        };

        List<ServiceInstance> instances1 = createTestInstances(2);
        List<ServiceInstance> instances2 = createTestInstances(3);

        // When
        ServiceInstance chosen1 = loadBalancer.choose("service-a", instances1, new HashMap<>());
        ServiceInstance chosen2 = loadBalancer.choose("service-b", instances2, new HashMap<>());

        // Then
        assertEquals("instance-0", chosen1.getInstanceId());
        assertEquals("instance-0", chosen2.getInstanceId());
    }

    // Helper method to create test service instances
    private List<ServiceInstance> createTestInstances(int count) {
        List<ServiceInstance> instances = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            instances.add(new ServiceInstance() {
                @Override
                public String getServiceName() {
                    return "test-service";
                }

                @Override
                public String getInstanceId() {
                    return "instance-" + index;
                }

                @Override
                public String getHost() {
                    return "localhost";
                }

                @Override
                public int getPort() {
                    return 8080 + index;
                }

                @Override
                public Map<String, String> getMetadata() {
                    return new HashMap<>();
                }

                @Override
                public boolean isEnabled() {
                    return true;
                }

                @Override
                public boolean isHealthy() {
                    return true;
                }
            });
        }
        return instances;
    }
}
